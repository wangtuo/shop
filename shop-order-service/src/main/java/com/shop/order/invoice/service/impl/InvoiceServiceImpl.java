package com.shop.order.invoice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.enums.OrderStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.order.invoice.entity.OrderInvoice;
import com.shop.order.invoice.mapper.OrderInvoiceMapper;
import com.shop.order.invoice.service.InvoiceService;
import com.shop.order.order.entity.Order;
import com.shop.order.order.dto.InvoiceRequest;
import com.shop.order.order.service.OrderQueryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 发票服务实现（design 5.5）。
 * <p>订单完成后由 {@link #issueDueInvoices()} 扫描开具（模拟生成发票号与 PDF 地址）；
 * REFUND_SUCCESS 全额退款时 {@link #redFlush(String)} 自动冲红。
 */
@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceServiceImpl.class);
    private static final int SCAN_LIMIT = 100;
    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderInvoiceMapper invoiceMapper;
    private final OrderQueryService orderQueryService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderDTO saveInvoice(String orderNo, Long userId, InvoiceRequest request) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (order.getStatus() != null && order.getStatus() == OrderStatuses.CANCELLED) {
            throw new BizException(ErrorCode.ORDER_STATUS_ERROR, "已取消订单不能开发票");
        }
        // 发票选择（含不开发票 0，design 5.5）登记落库并随订单聚合回显；开具扫描只取类型 1/2
        OrderInvoice invoice = invoiceMapper.selectOne(new LambdaQueryWrapper<OrderInvoice>()
                .eq(OrderInvoice::getOrderNo, orderNo));
        if (invoice != null) {
            if (invoice.getStatus() != null && invoice.getStatus() == 1) {
                throw new BizException(ErrorCode.ORDER_STATUS_ERROR, "发票已开具，不能修改");
            }
            if (invoice.getStatus() != null && invoice.getStatus() == 2) {
                throw new BizException(ErrorCode.ORDER_STATUS_ERROR, "发票已冲红，不能修改");
            }
        }
        validate(request);
        if (invoice == null) {
            invoice = new OrderInvoice();
            invoice.setOrderNo(orderNo);
            invoice.setUserId(order.getUserId());
            invoice.setMerchantId(order.getMerchantId());
            invoice.setStatus(0);
            apply(invoice, request);
            invoiceMapper.insert(invoice);
        } else {
            apply(invoice, request);
            invoiceMapper.updateById(invoice);
        }
        return orderQueryService.detail(orderNo, userId, null);
    }

    @Override
    public OrderDTO view(String orderNo, Long userId) {
        return orderQueryService.detail(orderNo, userId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int issueDueInvoices() {
        List<OrderInvoice> pending = invoiceMapper.selectList(new LambdaQueryWrapper<OrderInvoice>()
                .eq(OrderInvoice::getStatus, 0)
                .in(OrderInvoice::getInvoiceType, 1, 2)
                .last("LIMIT " + SCAN_LIMIT));
        int issued = 0;
        LocalDateTime now = LocalDateTime.now();
        for (OrderInvoice invoice : pending) {
            Order order;
            try {
                order = orderQueryService.requireByOrderNo(invoice.getOrderNo());
            } catch (BizException e) {
                log.warn("开票扫描找不到订单 orderNo={}", invoice.getOrderNo());
                continue;
            }
            // 订单完成（40）或售后关闭（70）后开具
            if (order.getStatus() == null
                    || (order.getStatus() != OrderStatuses.COMPLETED && order.getStatus() != OrderStatuses.CLOSED)) {
                continue;
            }
            String invoiceNo = "INV" + now.format(NO_FORMAT) + String.format("%04d", issued + 1);
            String pdfUrl = "/invoices/" + invoice.getOrderNo() + ".pdf";
            if (invoiceMapper.markIssued(invoice.getId(), invoiceNo, pdfUrl, now) > 0) {
                issued++;
                log.info("电子发票已开具 orderNo={} invoiceNo={} email={}",
                        invoice.getOrderNo(), invoiceNo, invoice.getEmail());
            }
        }
        return issued;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void redFlush(String orderNo) {
        int rows = invoiceMapper.markRedFlushed(orderNo, LocalDateTime.now());
        if (rows > 0) {
            log.info("退款触发发票自动冲红 orderNo={}", orderNo);
        }
    }

    private void apply(OrderInvoice invoice, InvoiceRequest request) {
        invoice.setInvoiceType(request.getInvoiceType() == null ? 0 : request.getInvoiceType());
        invoice.setContentScope(request.getContentScope() == null ? 1 : request.getContentScope());
        invoice.setTitleType("COMPANY".equals(request.getTitleType()) ? "COMPANY" : "PERSONAL");
        invoice.setCompanyName(request.getCompanyName() == null ? "" : request.getCompanyName());
        invoice.setTaxNo(request.getTaxNo() == null ? "" : request.getTaxNo());
        invoice.setEmail(request.getEmail() == null ? "" : request.getEmail());
    }

    private void validate(InvoiceRequest request) {
        int type = request.getInvoiceType() == null ? 0 : request.getInvoiceType();
        if (type != 0 && type != 1 && type != 2) {
            throw new BizException(ErrorCode.PARAM_INVALID, "发票类型非法");
        }
        if (type == 1 && isBlank(request.getEmail())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "电子发票必须填写接收邮箱");
        }
        if (type == 2) {
            if (!"COMPANY".equals(request.getTitleType())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "增值税专用发票必须为企业抬头");
            }
            if (isBlank(request.getCompanyName()) || isBlank(request.getTaxNo())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "企业发票必须填写公司名称与税号");
            }
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
