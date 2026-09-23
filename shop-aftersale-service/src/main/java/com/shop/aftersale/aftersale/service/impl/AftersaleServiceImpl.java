package com.shop.aftersale.aftersale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.aftersale.aftersale.dto.AftersaleApplyRequest;
import com.shop.aftersale.aftersale.dto.AftersaleDetailVO;
import com.shop.aftersale.aftersale.dto.AftersalePageQuery;
import com.shop.aftersale.aftersale.dto.ArbitrateRequest;
import com.shop.aftersale.aftersale.dto.EvidenceRequest;
import com.shop.aftersale.aftersale.dto.LogisticsRequest;
import com.shop.aftersale.aftersale.dto.PriceProtectTrialRequest;
import com.shop.aftersale.aftersale.dto.PriceProtectTrialVO;
import com.shop.aftersale.aftersale.entity.AftersaleDispute;
import com.shop.aftersale.aftersale.entity.AftersaleEvidence;
import com.shop.aftersale.aftersale.entity.AftersaleItem;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.aftersale.aftersale.entity.AftersaleRefund;
import com.shop.aftersale.aftersale.entity.AftersaleWindow;
import com.shop.aftersale.aftersale.entity.OrderItemRef;
import com.shop.aftersale.aftersale.entity.PriceProtectRecord;
import com.shop.aftersale.aftersale.entity.StatusLog;
import com.shop.aftersale.aftersale.enums.AftersaleCodes;
import com.shop.aftersale.aftersale.mapper.AftersaleDisputeMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleEvidenceMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleItemMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleOrderMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleRefundMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleWindowMapper;
import com.shop.aftersale.aftersale.mapper.OrderItemRefMapper;
import com.shop.aftersale.aftersale.mapper.PriceProtectRecordMapper;
import com.shop.aftersale.aftersale.mapper.StatusLogMapper;
import com.shop.aftersale.aftersale.service.AftersaleService;
import com.shop.aftersale.idgen.AftersaleNoGenerator;
import com.shop.aftersale.statemachine.AftersaleStateMachine;
import com.shop.aftersale.support.AftersaleDelayTopics;
import com.shop.aftersale.support.AftersaleEventPublisher;
import com.shop.aftersale.support.AftersalePolicy;
import com.shop.aftersale.support.AftersaleRefundStore;
import com.shop.aftersale.support.AftersaleTimeoutMessage;
import com.shop.aftersale.support.RefundCalculator;
import com.shop.api.aftersale.enums.AftersaleStatuses;
import com.shop.api.aftersale.enums.AftersaleTypes;
import com.shop.api.aftersale.enums.ArbitrationResults;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderItemDTO;
import com.shop.api.order.client.OrderClient;
import com.shop.api.pay.client.PayClient;
import com.shop.api.pay.dto.CreateRefundCommand;
import com.shop.api.pay.dto.RefundDTO;
import com.shop.api.pay.enums.RefundSources;
import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.dto.StockItemCommand;
import com.shop.api.product.dto.StockReturnCommand;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.feign.FeignResults;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.framework.web.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 售后主流程实现。金额一律 Long 分；所有状态流转经 {@link AftersaleStateMachine} 与条件 UPDATE。
 */
@Service
@RequiredArgsConstructor
public class AftersaleServiceImpl implements AftersaleService {

    private static final long SECONDS_2_DAY = 2 * 24 * 3600L;
    private static final long SECONDS_3_DAY = 3 * 24 * 3600L;
    private static final long SECONDS_5_DAY = 5 * 24 * 3600L;

    private final AftersaleOrderMapper orderMapper;
    private final AftersaleItemMapper itemMapper;
    private final AftersaleWindowMapper windowMapper;
    private final OrderItemRefMapper itemRefMapper;
    private final AftersaleRefundMapper refundMapper;
    private final AftersaleDisputeMapper disputeMapper;
    private final AftersaleEvidenceMapper evidenceMapper;
    private final PriceProtectRecordMapper priceProtectMapper;
    private final StatusLogMapper statusLogMapper;

    private final OrderClient orderClient;
    private final PayClient payClient;
    private final ProductClient productClient;

    private final AftersaleStateMachine stateMachine;
    private final AftersalePolicy policy;
    private final RefundCalculator calculator;
    private final AftersaleEventPublisher eventPublisher;
    private final AftersaleNoGenerator noGenerator;
    // P1-1：售后超时延时事件走 outbox，与售后单状态同事务提交/回滚
    private final OutboxPublisher outboxPublisher;
    // P1-11/P2-8：退款单 REQUIRES_NEW 独立事务落库
    private final AftersaleRefundStore refundStore;

    // ============================ 申请 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String apply(AftersaleApplyRequest req, Long userId) {
        OrderDTO order = loadOrder(req.getOrderNo());
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能为自己的订单申请售后");
        }
        AftersaleWindow window = materializeWindow(order, windowMapper.selectByOrderNo(order.getOrderNo()));
        if (window.getId() == null) {
            windowMapper.insert(window);
        }
        int type = req.getType();
        String notAllowed = policy.checkApplicable(type, order.getStatus(), window, order.getCreateTime());
        if (notAllowed != null) {
            throw new BizException(ErrorCode.AFTERSALE_NOT_ALLOW, notAllowed);
        }
        if (type == AftersaleTypes.PRICE_PROTECT
                && priceProtectMapper.selectActiveByOrderNo(order.getOrderNo()) != null) {
            throw new BizException(ErrorCode.REPEAT_SUBMIT, "该订单已申请过价保");
        }

        LocalDateTime now = policy.now();
        String no = noGenerator.nextAftersaleNo();
        Map<Long, OrderItemDTO> dtoMap = order.getItems().stream()
                .collect(Collectors.toMap(OrderItemDTO::getOrderItemId, i -> i, (a, b) -> a));

        long refundFen;
        int pointsRefund = 0;
        List<AftersaleItem> aftersaleItems;
        Long originalUnit = null;
        Long currentPrice = null;

        if (type == AftersaleTypes.PRICE_PROTECT) {
            if (req.getItems().size() != 1) {
                throw new BizException(ErrorCode.PARAM_INVALID, "价保仅支持单明细申请");
            }
            PriceProtectTrialVO trial = doTrial(order, window, req.getItems().get(0).getOrderItemId());
            if (!trial.isEligible()) {
                throw new BizException(ErrorCode.AFTERSALE_NOT_ALLOW, trial.getReason());
            }
            originalUnit = trial.getOriginalUnitFen();
            currentPrice = trial.getCurrentPriceFen();
            refundFen = trial.getDiffTotalFen();
            OrderItemDTO di = dtoMap.get(trial.getOrderItemId());
            OrderItemRef ref = getOrCreateRef(window, di);
            occupy(ref, no);
            aftersaleItems = List.of(buildItem(no, di, ref, di.getQty(), refundFen));
        } else {
            List<RefundCalculator.ItemRefundInput> inputs = new ArrayList<>();
            List<OrderItemRef> refs = new ArrayList<>();
            for (AftersaleApplyRequest.Item line : req.getItems()) {
                OrderItemDTO di = dtoMap.get(line.getOrderItemId());
                if (di == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "订单明细不存在: " + line.getOrderItemId());
                }
                OrderItemRef ref = getOrCreateRef(window, di);
                if (StringUtils.hasText(ref.getActiveNo())) {
                    throw new BizException(ErrorCode.CONFLICT, "该明细已有进行中售后");
                }
                if (line.getQty() > di.getQty()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "售后数量超过购买数量");
                }
                refs.add(ref);
                inputs.add(new RefundCalculator.ItemRefundInput(di.getOrderItemId(),
                        ref.getPaidFen() - ref.getRefundedFen(), di.getQty(), line.getQty()));
            }
            List<Long> lineRefunds = calculator.calcItemRefunds(inputs);
            long itemsRefund = 0L;
            aftersaleItems = new ArrayList<>();
            for (int i = 0; i < refs.size(); i++) {
                OrderItemRef ref = refs.get(i);
                OrderItemDTO di = dtoMap.get(ref.getOrderItemId());
                long lineRefund = (type == AftersaleTypes.EXCHANGE || type == AftersaleTypes.RESHIP)
                        ? 0L : lineRefunds.get(i);
                if (type != AftersaleTypes.EXCHANGE && type != AftersaleTypes.RESHIP && lineRefund <= 0) {
                    throw new BizException(ErrorCode.AFTERSALE_AMOUNT_EXCEED, "可退金额为0，无法申请");
                }
                itemsRefund += lineRefund;
                aftersaleItems.add(buildItem(no, di, ref, req.getItems().get(i).getQty(), lineRefund));
            }
            int side = req.getResponsibilitySide() == null ? 2 : req.getResponsibilitySide();
            long freightComp = calculator.freightCompensation(side, nz(req.getReturnFreightFen()));
            refundFen = itemsRefund + freightComp;
            long productBase = window.getProductPayFen() != null && window.getProductPayFen() > 0
                    ? window.getProductPayFen() : Math.max(1L, nz(order.getPayFen()) - nz(order.getFreightFen()));
            long pointsUsed = window.getUsedPoints() != null ? window.getUsedPoints()
                    : nz(window.getUsedPointsFen());
            pointsRefund = calculator.pointsToRefund(pointsUsed, itemsRefund, productBase);
            for (OrderItemRef ref : refs) {
                occupy(ref, no);
            }
        }

        int refundType = isFullRefund(order, refundFen) ? 1 : 2;
        AftersaleOrder o = new AftersaleOrder();
        o.setAftersaleNo(no);
        o.setOrderNo(order.getOrderNo());
        o.setUserId(userId);
        o.setMerchantId(merchantOf(order));
        o.setType(type);
        o.setStatus(AftersaleStatuses.WAIT_MERCHANT_AUDIT);
        o.setReason(req.getReason());
        o.setResponsibilitySide(req.getResponsibilitySide() == null ? 2 : req.getResponsibilitySide());
        o.setApplyTime(now);
        o.setShippedTime(window.getShippedTime());
        o.setConfirmTime(window.getConfirmTime());
        o.setFreeAftersaleDeadline(window.getFreeAftersaleDeadline());
        o.setWarrantyDeadline(window.getWarrantyDeadline());
        o.setAuditDeadline(policy.auditDeadline(now));
        o.setRefundFen(refundFen);
        o.setFreightRefundFen(type == AftersaleTypes.PRICE_PROTECT ? 0L
                : calculator.freightCompensation(o.getResponsibilitySide(), nz(req.getReturnFreightFen())));
        o.setPointsRefund(pointsRefund);
        o.setRefundType(refundType);
        o.setResubmitTimes(0);
        if (type == AftersaleTypes.EXCHANGE) {
            o.setExchangeSkuId(req.getExchangeSkuId() != null ? req.getExchangeSkuId()
                    : aftersaleItems.get(0).getSkuId());
        }
        if (originalUnit != null) {
            o.setOriginalPriceFen(originalUnit);
            o.setCurrentPriceFen(currentPrice);
        }
        orderMapper.insert(o);
        for (AftersaleItem ai : aftersaleItems) {
            itemMapper.insert(ai);
        }
        if (type == AftersaleTypes.PRICE_PROTECT) {
            PriceProtectRecord pp = new PriceProtectRecord();
            pp.setOrderNo(order.getOrderNo());
            pp.setAftersaleNo(no);
            pp.setOrderItemId(aftersaleItems.get(0).getOrderItemId());
            pp.setSkuId(aftersaleItems.get(0).getSkuId());
            pp.setOriginalPriceFen(originalUnit);
            pp.setCurrentPriceFen(currentPrice);
            pp.setDiffFen(refundFen);
            pp.setBigPromotion(Boolean.TRUE.equals(req.getBigPromotion()) ? 1 : 0);
            pp.setStatus(AftersaleCodes.PRICE_APPLIED);
            priceProtectMapper.insert(pp);
        }
        long applyLogId = log(no, null, AftersaleStatuses.WAIT_MERCHANT_AUDIT, userId, AftersaleCodes.ROLE_USER, "用户申请售后");
        if (type == AftersaleTypes.REFUND_ONLY || type == AftersaleTypes.RETURN_REFUND
                || type == AftersaleTypes.EXCHANGE) {
            sendDelay(no, AftersaleDelayTopics.KIND_AUDIT, SECONDS_2_DAY, null,
                    AftersaleTimeoutMessage.deadlineRoundKey(o.getAuditDeadline()));
        }
        eventPublisher.publish(o, null, AftersaleStatuses.WAIT_MERCHANT_AUDIT, null, aftersaleItems, applyLogId);
        return no;
    }

    // ============================ 用户撤销 / 修改重提 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String no, Long userId) {
        AftersaleOrder o = requireAftersale(no);
        requireOwner(o, userId);
        int s = o.getStatus();
        if (s != AftersaleStatuses.WAIT_MERCHANT_AUDIT
                && s != AftersaleStatuses.WAIT_BUYER_RETURN
                && s != AftersaleStatuses.REJECTED) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR, "当前状态不允许撤销");
        }
        releaseRefs(no);
        o.setCancelTime(policy.now());
        orderMapper.updateById(o);
        change(o, AftersaleStatuses.CANCELED, userId, AftersaleCodes.ROLE_USER, "用户撤销", null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resubmit(String no, AftersaleApplyRequest req, Long userId) {
        AftersaleOrder o = requireAftersale(no);
        requireOwner(o, userId);
        if (o.getStatus() != AftersaleStatuses.REJECTED) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR, "仅已拒绝售后可修改重提");
        }
        OrderDTO order = loadOrder(o.getOrderNo());
        AftersaleWindow window = materializeWindow(order, windowMapper.selectByOrderNo(o.getOrderNo()));
        String blocked = policy.checkApplicable(o.getType(), order.getStatus(), window, order.getCreateTime());
        if (blocked != null) {
            throw new BizException(ErrorCode.AFTERSALE_NOT_ALLOW, blocked);
        }
        List<AftersaleItem> items = itemMapper.selectByAftersaleNo(no);
        for (AftersaleItem it : items) {
            if (itemRefMapper.occupy(it.getOrderItemId(), no) == 0) {
                throw new BizException(ErrorCode.CONFLICT, "明细已有进行中售后，无法重提");
            }
        }
        o.setReason(StringUtils.hasText(req.getReason()) ? req.getReason() : o.getReason());
        o.setResponsibilitySide(req.getResponsibilitySide() == null ? o.getResponsibilitySide()
                : req.getResponsibilitySide());
        o.setRejectReason("");
        o.setResubmitTimes(nzInt(o.getResubmitTimes()) + 1);
        o.setAuditDeadline(policy.auditDeadline(policy.now()));
        orderMapper.updateById(o);
        change(o, AftersaleStatuses.WAIT_MERCHANT_AUDIT, userId, AftersaleCodes.ROLE_USER, "用户修改重提", null);
        // R4-25：重提是同一售后单第二次进入待审核(10)，延时行必须带新一轮截止点维度，
        // 否则与申请时首轮行撞 outbox uk(topic,tag,biz_key)，重提事务整体回滚。
        sendDelay(no, AftersaleDelayTopics.KIND_AUDIT, SECONDS_2_DAY, null,
                AftersaleTimeoutMessage.deadlineRoundKey(o.getAuditDeadline()));
    }

    // ============================ 商家审核 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(String no, Long merchantId, boolean agree, String rejectReason) {
        AftersaleOrder o = requireAftersale(no);
        requireMerchant(o, merchantId);
        if (o.getStatus() != AftersaleStatuses.WAIT_MERCHANT_AUDIT) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR, "售后单不在待审核状态");
        }
        if (agree) {
            approve(o, AftersaleCodes.ROLE_MERCHANT, merchantId);
        } else {
            reject(o, rejectReason, merchantId, AftersaleCodes.ROLE_MERCHANT);
        }
    }

    private void approve(AftersaleOrder o, int role, Long operatorId) {
        LocalDateTime now = policy.now();
        o.setAuditTime(now);
        o.setRejectReason("");
        orderMapper.updateById(o);
        switch (o.getType()) {
            case AftersaleTypes.REFUND_ONLY, AftersaleTypes.PRICE_PROTECT -> {
                startRefund(o, o.getRefundFen(), role, operatorId, "商家同意退款");
            }
            case AftersaleTypes.RETURN_REFUND, AftersaleTypes.EXCHANGE ->
                    change(o, AftersaleStatuses.WAIT_BUYER_RETURN, operatorId, role, "商家同意，等待买家退货", null);
            case AftersaleTypes.RESHIP -> {
                o.setExchangeShipDeadline(null);
                change(o, AftersaleStatuses.WAIT_EXCHANGE_SHIP, operatorId, role, "商家同意补发", null);
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "未知售后类型");
        }
    }

    private void reject(AftersaleOrder o, String reason, Long operatorId, int role) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "拒绝原因不能为空");
        }
        o.setRejectReason(reason);
        o.setRejectTime(policy.now());
        orderMapper.updateById(o);
        releaseRefs(o.getAftersaleNo());
        change(o, AftersaleStatuses.REJECTED, operatorId, role, "商家拒绝：" + reason, null);
    }

    // ============================ 退货物流 / 商家收货 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fillReturnLogistics(String no, Long userId, LogisticsRequest req) {
        AftersaleOrder o = requireAftersale(no);
        requireOwner(o, userId);
        if (o.getStatus() != AftersaleStatuses.WAIT_BUYER_RETURN) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR, "当前状态不允许填写退货物流");
        }
        LocalDateTime now = policy.now();
        o.setReturnCompany(req.getCompany());
        o.setReturnLogisticsNo(req.getLogisticsNo());
        o.setReturnShipTime(now);
        o.setReceiveDeadline(policy.receiveDeadline(now));
        orderMapper.updateById(o);
        change(o, AftersaleStatuses.MERCHANT_RECEIVING, userId, AftersaleCodes.ROLE_USER,
                "买家已寄回", req.getLogisticsNo());
        // R4-25：拒收→重提→再次寄回会第二次登记 receive 延时行，带轮次维度避免同三元组冲突。
        sendDelay(no, AftersaleDelayTopics.KIND_RECEIVE, SECONDS_3_DAY, null,
                AftersaleTimeoutMessage.deadlineRoundKey(o.getReceiveDeadline()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void merchantReceive(String no, Long merchantId, boolean accept, String rejectReason) {
        AftersaleOrder o = requireAftersale(no);
        requireMerchant(o, merchantId);
        if (o.getStatus() != AftersaleStatuses.MERCHANT_RECEIVING) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR, "当前状态不允许确认收货");
        }
        if (accept) {
            confirmReceive(o, AftersaleCodes.ROLE_MERCHANT, merchantId);
        } else {
            o.setRejectReason(StringUtils.hasText(rejectReason) ? rejectReason : "商家拒收");
            orderMapper.updateById(o);
            releaseRefs(no);
            change(o, AftersaleStatuses.REJECTED, merchantId, AftersaleCodes.ROLE_MERCHANT, "商家拒收", null);
        }
    }

    private void confirmReceive(AftersaleOrder o, int role, Long operatorId) {
        LocalDateTime now = policy.now();
        o.setMerchantReceiveTime(now);
        orderMapper.updateById(o);
        if (o.getResponsibilitySide() != null
                && o.getResponsibilitySide() == com.shop.api.aftersale.enums.ResponsibilitySide.BUYER) {
            // 买家责任：正常商品回可售库存（与 AFTERSALE_CHANGED 事件消费者以 aftersaleNo 幂等互斥）
            returnStock(o);
        }
        if (o.getType() == AftersaleTypes.RETURN_REFUND) {
            startRefund(o, o.getRefundFen(), role, operatorId, "商家确认收货，发起退款");
        } else {
            o.setExchangeShipDeadline(policy.exchangeShipDeadline(now));
            orderMapper.updateById(o);
            change(o, AftersaleStatuses.WAIT_EXCHANGE_SHIP, operatorId, role, "商家已收货，等待换货发出", null);
            sendDelay(o.getAftersaleNo(), AftersaleDelayTopics.KIND_EXCHANGE_SHIP, SECONDS_5_DAY, null,
                    AftersaleTimeoutMessage.deadlineRoundKey(o.getExchangeShipDeadline()));
        }
    }

    // ============================ 换货 / 补发 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void shipExchange(String no, Long merchantId, LogisticsRequest req) {
        AftersaleOrder o = requireAftersale(no);
        requireMerchant(o, merchantId);
        if (o.getStatus() != AftersaleStatuses.WAIT_EXCHANGE_SHIP) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR, "当前状态不允许发货");
        }
        if (o.getType() != AftersaleTypes.EXCHANGE && o.getType() != AftersaleTypes.RESHIP) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅换货/补发货可发货");
        }
        Long skuId = req.getExchangeSkuId() != null ? req.getExchangeSkuId()
                : (o.getExchangeSkuId() != null ? o.getExchangeSkuId() : firstSku(no));
        if (o.getType() == AftersaleTypes.EXCHANGE) {
            Integer qty = itemMapper.selectByAftersaleNo(no).stream()
                    .mapToInt(AftersaleItem::getQty).sum();
            Result<Boolean> saleable = productClient.saleable(skuId, qty);
            if (saleable == null || !saleable.isSuccess() || !Boolean.TRUE.equals(saleable.getData())) {
                throw new BizException(ErrorCode.STOCK_NOT_ENOUGH, "换货商品库存不足");
            }
            o.setExchangeSkuId(skuId);
        }
        LocalDateTime now = policy.now();
        o.setExchangeCompany(req.getCompany());
        o.setExchangeLogisticsNo(req.getLogisticsNo());
        o.setExchangeShipTime(now);
        orderMapper.updateById(o);
        change(o, AftersaleStatuses.EXCHANGE_SHIPPED, merchantId, AftersaleCodes.ROLE_MERCHANT,
                "换货/补发已发货", req.getLogisticsNo());
        change(o, AftersaleStatuses.EXCHANGE_WAIT_RECEIVE, merchantId, AftersaleCodes.ROLE_MERCHANT,
                "等待用户签收", req.getLogisticsNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmExchange(String no, Long userId) {
        AftersaleOrder o = requireAftersale(no);
        requireOwner(o, userId);
        if (o.getStatus() != AftersaleStatuses.EXCHANGE_WAIT_RECEIVE) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR, "当前状态不允许确认签收");
        }
        o.setExchangeReceiveTime(policy.now());
        o.setFinishTime(policy.now());
        orderMapper.updateById(o);
        releaseRefs(no);
        change(o, AftersaleStatuses.FINISHED, userId, AftersaleCodes.ROLE_USER, "用户签收，售后完成",
                o.getExchangeLogisticsNo());
    }

    // ============================ 平台介入 / 仲裁 ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyIntervene(String no, Long userId) {
        AftersaleOrder o = requireAftersale(no);
        requireOwner(o, userId);
        int s = o.getStatus();
        LocalDateTime now = policy.now();
        boolean rejected = s == AftersaleStatuses.REJECTED;
        boolean auditTimeout = s == AftersaleStatuses.WAIT_MERCHANT_AUDIT
                && o.getAuditDeadline() != null && !now.isBefore(o.getAuditDeadline());
        boolean receiveTimeout = s == AftersaleStatuses.MERCHANT_RECEIVING
                && o.getReceiveDeadline() != null && !now.isBefore(o.getReceiveDeadline());
        if (!rejected && !auditTimeout && !receiveTimeout) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR,
                    "商家已拒绝或超时未处理时才可申请平台介入");
        }
        if (disputeMapper.selectByAftersaleNo(no) != null) {
            throw new BizException(ErrorCode.REPEAT_SUBMIT, "已申请平台介入");
        }
        AftersaleDispute d = new AftersaleDispute();
        d.setAftersaleNo(no);
        d.setOrderNo(o.getOrderNo());
        d.setUserId(userId);
        d.setMerchantId(o.getMerchantId());
        d.setStatus(AftersaleCodes.DISPUTE_EVIDENCING);
        d.setApplyTime(now);
        d.setEvidenceDeadline(policy.evidenceDeadline(now));
        d.setArbitrateDeadline(policy.arbitrateDeadline(d.getEvidenceDeadline()));
        d.setResult(0);
        d.setAwardFen(0L);
        disputeMapper.insert(d);

        o.setInterveneTime(now);
        orderMapper.updateById(o);
        change(o, AftersaleStatuses.PLATFORM_INTERVENING, userId, AftersaleCodes.ROLE_USER,
                "用户申请平台介入", null);
        sendDelay(no, AftersaleDelayTopics.KIND_EVIDENCE, SECONDS_3_DAY, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitEvidence(String no, int side, Long operatorId, EvidenceRequest req) {
        AftersaleDispute d = disputeMapper.selectByAftersaleNo(no);
        if (d == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "平台介入单不存在");
        }
        if (d.getStatus() != AftersaleCodes.DISPUTE_EVIDENCING) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR, "举证期已结束");
        }
        if (policy.now().isAfter(d.getEvidenceDeadline())) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR, "已超过3天举证期限");
        }
        AftersaleEvidence e = new AftersaleEvidence();
        e.setAftersaleNo(no);
        e.setSide(side);
        e.setUserId(operatorId);
        e.setEvidenceType(req.getEvidenceType());
        e.setContent(req.getContent());
        e.setMediaUrls(req.getMediaUrls());
        evidenceMapper.insert(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void arbitrate(String no, ArbitrateRequest req) {
        // H-1：平台介入仲裁仅平台运营可执行（控制器层亦做同样校验，纵深防御）。
        // O5：必须取回真实平台运营身份——状态日志/退款单的 operatorId 只能记录该 ID，
        // 禁止硬编码 0L（0L 是系统自动流转专用，见 autoApprove/autoConfirmReceive）。
        LoginUser admin = com.shop.aftersale.support.WebIdentity.requirePlatformAdminUser();
        Long operatorId = admin.getUserId();
        AftersaleDispute d = disputeMapper.selectByAftersaleNo(no);
        if (d == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "平台介入单不存在");
        }
        AftersaleOrder o = requireAftersale(no);
        if (o.getStatus() != AftersaleStatuses.PLATFORM_INTERVENING) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR, "售后单不在平台介入中");
        }
        LocalDateTime now = policy.now();
        d.setStatus(AftersaleCodes.DISPUTE_DONE);
        d.setResult(req.getResult());
        d.setAwardFen(nz(req.getAwardFen()));
        d.setArbitrateRemark(req.getRemark());
        d.setArbitrateTime(now);
        disputeMapper.updateById(d);

        switch (req.getResult()) {
            case ArbitrationResults.MERCHANT_WIN -> {
                o.setRejectReason(StringUtils.hasText(req.getRemark()) ? req.getRemark() : "平台裁决：维持商家处理");
                orderMapper.updateById(o);
                releaseRefs(no);
                change(o, AftersaleStatuses.REJECTED, operatorId, AftersaleCodes.ROLE_PLATFORM,
                        "平台仲裁：商家胜诉", null);
            }
            case ArbitrationResults.BUYER_WIN -> {
                if (o.getType() == AftersaleTypes.EXCHANGE || o.getType() == AftersaleTypes.RESHIP) {
                    o.setExchangeShipDeadline(policy.exchangeShipDeadline(now));
                    orderMapper.updateById(o);
                    change(o, AftersaleStatuses.WAIT_EXCHANGE_SHIP, operatorId, AftersaleCodes.ROLE_PLATFORM,
                            "平台仲裁：买家胜诉，继续换货/补发", null);
                    sendDelay(no, AftersaleDelayTopics.KIND_EXCHANGE_SHIP, SECONDS_5_DAY, null,
                            AftersaleTimeoutMessage.deadlineRoundKey(o.getExchangeShipDeadline()));
                } else {
                    startRefund(o, o.getRefundFen(), AftersaleCodes.ROLE_PLATFORM, operatorId, "平台仲裁：买家胜诉，执行退款");
                }
            }
            case ArbitrationResults.PARTIAL -> {
                long award = nz(req.getAwardFen());
                if (award <= 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "部分支持时裁定金额必须大于0");
                }
                if (award > nz(o.getRefundFen())) {
                    throw new BizException(ErrorCode.AFTERSALE_AMOUNT_EXCEED, "裁定金额不能超过申请金额");
                }
                o.setRefundFen(award);
                orderMapper.updateById(o);
                startRefund(o, award, AftersaleCodes.ROLE_PLATFORM, operatorId, "平台仲裁：部分支持");
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "未知仲裁结果");
        }
    }

    // ============================ 价保试算 ============================

    @Override
    public PriceProtectTrialVO trialPriceProtect(PriceProtectTrialRequest req, Long userId) {
        OrderDTO order = loadOrder(req.getOrderNo());
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能试算自己的订单");
        }
        AftersaleWindow window = materializeWindow(order,
                windowMapper.selectByOrderNo(order.getOrderNo()));
        if (Boolean.TRUE.equals(req.getBigPromotion())) {
            window.setOrderType(4);
        }
        return doTrial(order, window, req.getOrderItemId());
    }

    private PriceProtectTrialVO doTrial(OrderDTO order, AftersaleWindow window, Long orderItemId) {
        OrderItemDTO item = order.getItems().stream()
                .filter(i -> i.getOrderItemId().equals(orderItemId)).findFirst()
                .orElse(null);
        PriceProtectTrialVO.PriceProtectTrialVOBuilder b = PriceProtectTrialVO.builder()
                .orderNo(order.getOrderNo()).orderItemId(orderItemId);
        if (item == null) {
            return b.eligible(false).reason("订单明细不存在").build();
        }
        b.skuId(item.getSkuId()).qty(item.getQty());
        if (!policy.priceProtectSupported(order.getOrderType())) {
            return b.eligible(false).reason("秒杀/拼团活动价不支持价保").build();
        }
        if (!policy.inPriceProtectWindow(order.getCreateTime(), window.getOrderType(), policy.now())) {
            return b.eligible(false)
                    .reason("已超出价保期（" + policy.priceProtectDays(window.getOrderType()) + "天）").build();
        }
        if (priceProtectMapper.selectActiveByOrderNo(order.getOrderNo()) != null
                || orderMapper.countPriceProtect(order.getOrderNo()) > 0) {
            return b.eligible(false).reason("该订单已享受过价保").build();
        }
        SkuDTO sku = FeignResults.unwrap(productClient.getSku(item.getSkuId()));
        long current = sku.getSalePriceFen() == null ? 0L : sku.getSalePriceFen();
        long originalUnit = item.getPaidFen() / item.getQty();
        long diffUnit = policy.priceProtectDiff(originalUnit, current);
        return b.originalUnitFen(originalUnit).currentPriceFen(current).diffUnitFen(diffUnit)
                .diffTotalFen(diffUnit * item.getQty())
                .eligible(diffUnit > 0)
                .reason(diffUnit > 0 ? null : "当前普通售价未低于购买价，不满足价保").build();
    }

    // ============================ 查询 ============================

    @Override
    public AftersaleDetailVO detail(String no, LoginUser viewer) {
        AftersaleOrder o = requireAftersale(no);
        if (viewer == null || viewer.getUserId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        // H-2：平台运营放行
        if (viewer.getUserType() != null && viewer.getUserType() == 2) {
            return buildDetail(no);
        }
        boolean buyer = viewer.getUserId().equals(o.getUserId());
        boolean merchant = viewer.getMerchantId() != null
                && viewer.getMerchantId().equals(o.getMerchantId());
        if (!buyer && !merchant) {
            // 买家/归属商户二选一，不匹配 403（不泄露单号是否存在以外的信息）
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看该售后单");
        }
        return buildDetail(no);
    }

    private AftersaleDetailVO buildDetail(String no) {
        AftersaleDetailVO vo = new AftersaleDetailVO();
        vo.setAftersale(requireAftersale(no));
        vo.setItems(itemMapper.selectByAftersaleNo(no));
        return vo;
    }

    @Override
    public PageResult<AftersaleOrder> pageUser(AftersalePageQuery q, Long userId) {
        Page<AftersaleOrder> page = new Page<>(q.safePageNum(), q.safePageSize());
        LambdaQueryWrapper<AftersaleOrder> w = new LambdaQueryWrapper<AftersaleOrder>()
                .eq(AftersaleOrder::getUserId, userId)
                .eq(q.getStatus() != null, AftersaleOrder::getStatus, q.getStatus())
                .eq(q.getType() != null, AftersaleOrder::getType, q.getType())
                .eq(StringUtils.hasText(q.getOrderNo()), AftersaleOrder::getOrderNo, q.getOrderNo())
                .orderByDesc(AftersaleOrder::getId);
        orderMapper.selectPage(page, w);
        return PageResult.of(q.safePageNum(), q.safePageSize(), page.getTotal(), page.getRecords());
    }

    @Override
    public PageResult<AftersaleOrder> pageMerchant(AftersalePageQuery q, Long merchantId) {
        Page<AftersaleOrder> page = new Page<>(q.safePageNum(), q.safePageSize());
        LambdaQueryWrapper<AftersaleOrder> w = new LambdaQueryWrapper<AftersaleOrder>()
                .eq(AftersaleOrder::getMerchantId, merchantId)
                .eq(q.getStatus() != null, AftersaleOrder::getStatus, q.getStatus())
                .eq(q.getType() != null, AftersaleOrder::getType, q.getType())
                .eq(StringUtils.hasText(q.getOrderNo()), AftersaleOrder::getOrderNo, q.getOrderNo())
                .orderByDesc(AftersaleOrder::getId);
        orderMapper.selectPage(page, w);
        return PageResult.of(q.safePageNum(), q.safePageSize(), page.getTotal(), page.getRecords());
    }

    // ============================ 超时自动流转（双保险） ============================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void autoApprove(String no) {
        AftersaleOrder o = requireAftersale(no);
        if (o.getStatus() != AftersaleStatuses.WAIT_MERCHANT_AUDIT) {
            return;
        }
        if (o.getType() != AftersaleTypes.REFUND_ONLY && o.getType() != AftersaleTypes.RETURN_REFUND
                && o.getType() != AftersaleTypes.EXCHANGE) {
            return;
        }
        approve(o, AftersaleCodes.ROLE_SYSTEM, 0L);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void autoConfirmReceive(String no) {
        AftersaleOrder o = requireAftersale(no);
        if (o.getStatus() != AftersaleStatuses.MERCHANT_RECEIVING) {
            return;
        }
        confirmReceive(o, AftersaleCodes.ROLE_SYSTEM, 0L);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void autoConvertExchangeToRefund(String no) {
        AftersaleOrder o = requireAftersale(no);
        if (o.getStatus() != AftersaleStatuses.WAIT_EXCHANGE_SHIP
                || o.getType() != AftersaleTypes.EXCHANGE) {
            return;
        }
        long amount = itemMapper.selectByAftersaleNo(no).stream()
                .mapToLong(i -> nz(i.getRefundFen()) > 0 ? nz(i.getRefundFen()) : nz(i.getPaidFen())).sum();
        if (amount <= 0) {
            throw new BizException(ErrorCode.AFTERSALE_AMOUNT_EXCEED, "换货转退款金额异常");
        }
        o.setRefundFen(amount);
        o.setRefundType(2);
        orderMapper.updateById(o);
        startRefund(o, amount, AftersaleCodes.ROLE_SYSTEM, 0L, "换货5天未发货，自动转退款");
    }

    // ============================ 内部辅助 ============================

    /**
     * 落退款单 + 调支付域 + 售后单推进到退款中（40）。
     *
     * <p>P1-11：refundNo 由售后域生成，在调用支付域之前先以 REQUIRES_NEW 独立事务提交落库，
     * 外层事务之后任何回滚都不会抹掉退款单；Feign 重试 / 售后单重新流转时复用同一 refundNo
     * （pay 侧 {@code RefundServiceImpl.refund} 按 refundNo 幂等返回），不再产生新的孤儿退款单。
     * P2-8：调支付域失败后 FAIL 状态同样以 REQUIRES_NEW 独立事务落库，不随外层回滚丢失。</p>
     */
    private void startRefund(AftersaleOrder o, long amount, int role, Long operatorId, String remark) {
        if (amount <= 0) {
            throw new BizException(ErrorCode.AFTERSALE_AMOUNT_EXCEED, "退款金额必须大于0");
        }
        AftersaleRefund refund = refundMapper.selectByAftersaleNo(o.getAftersaleNo());
        if (refund == null) {
            refund = new AftersaleRefund();
            refund.setRefundNo(noGenerator.nextRefundNo());
            refund.setAftersaleNo(o.getAftersaleNo());
            refund.setOrderNo(o.getOrderNo());
            refund.setUserId(o.getUserId());
            refund.setAmountFen(amount);
            refund.setRefundType(o.getRefundType() == null ? 2 : o.getRefundType());
            refund.setStatus(AftersaleCodes.REFUND_WAIT);
            // 独立事务先落库：refundNo 在 Feign 调用前已提交（P1-11）
            refundStore.insertWaitingInNewTx(refund);
            invokePayRefund(o, refund, amount);
        } else if (refund.getStatus() != null
                && (refund.getStatus() == AftersaleCodes.REFUND_WAIT
                || refund.getStatus() == AftersaleCodes.REFUND_FAIL)) {
            // WAIT=崩溃在调用窗口、FAIL=上次调用支付域失败：复用同一 refundNo 再次发起（pay 侧幂等）
            invokePayRefund(o, refund, amount);
        } else {
            // PROCESSING/SUCCESS：支付域已受理/已成功，幂等推进售后单并等待 REFUND_SUCCESS 事件
            o.setRefundNo(refund.getRefundNo());
            orderMapper.updateById(o);
        }
        change(o, AftersaleStatuses.REFUNDING, operatorId, role, remark, null);
    }

    /** 调用支付域退款并把本地退款单推进到退款中；失败以独立事务置 FAIL（P2-8）。 */
    private void invokePayRefund(AftersaleOrder o, AftersaleRefund refund, long amount) {
        CreateRefundCommand cmd = CreateRefundCommand.builder()
                .refundNo(refund.getRefundNo())
                .orderNo(o.getOrderNo())
                .aftersaleNo(o.getAftersaleNo())
                .userId(o.getUserId())
                .amountFen(amount)
                .refundType(refund.getRefundType())
                .source(o.getType() == AftersaleTypes.PRICE_PROTECT
                        ? RefundSources.PRICE_PROTECT.getCode() : RefundSources.AFTERSALE.getCode())
                .operatorType(0)
                .reason(o.getReason())
                .build();
        RefundDTO dto;
        try {
            dto = FeignResults.unwrap(payClient.refund(cmd));
        } catch (BizException ex) {
            // FAIL 独立事务落库：外层事务随后回滚售后单变更，但退款单 FAIL + refundNo 保留（P2-8）
            refundStore.markFailInNewTx(refund.getRefundNo(), ex.getMessage());
            throw ex;
        }
        // pay 侧按 refundNo 幂等返回：若支付域退款单本身已是 FAIL，本地同步置失败
        if (dto == null || (dto.getStatus() != null
                && dto.getStatus() == com.shop.api.pay.enums.RefundStatuses.FAIL.getCode())) {
            String reason = dto == null ? "支付域退款失败" : "支付域退款单为失败状态";
            refundStore.markFailInNewTx(refund.getRefundNo(), reason);
            throw new BizException(ErrorCode.DEPENDENCY_FAIL, reason);
        }
        int updated = refundMapper.updateStatus(refund.getRefundNo(), AftersaleCodes.REFUND_WAIT,
                AftersaleCodes.REFUND_PROCESSING, dto.getPayMethod(), null);
        if (updated == 0) {
            // FAIL 重试路径：允许 FAIL → PROCESSING
            updated = refundMapper.updateStatus(refund.getRefundNo(), AftersaleCodes.REFUND_FAIL,
                    AftersaleCodes.REFUND_PROCESSING, dto.getPayMethod(), null);
        }
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "退款单状态并发冲突");
        }
        o.setRefundNo(refund.getRefundNo());
        orderMapper.updateById(o);
    }

    private void change(AftersaleOrder o, int to, Long operatorId, int role, String remark, String logisticsNo) {
        int from = o.getStatus();
        stateMachine.assertTransition(from, to);
        int rows = orderMapper.updateStatus(o.getAftersaleNo(), from, to);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "售后单状态已变更，请刷新重试");
        }
        o.setStatus(to);
        if (to == AftersaleStatuses.FINISHED) {
            o.setFinishTime(policy.now());
            orderMapper.updateById(o);
        }
        long transitionLogId = log(o.getAftersaleNo(), from, to, operatorId, role, remark);
        eventPublisher.publish(o, from, to, logisticsNo,
                itemMapper.selectByAftersaleNo(o.getAftersaleNo()), transitionLogId);
    }

    private long log(String no, Integer oldStatus, int newStatus, Long operatorId, int role, String remark) {
        StatusLog l = new StatusLog();
        l.setAftersaleNo(no);
        l.setOldStatus(oldStatus);
        l.setNewStatus(newStatus);
        l.setOperatorId(operatorId == null ? 0L : operatorId);
        l.setOperatorRole(role);
        l.setRemark(remark);
        statusLogMapper.insert(l);
        return l.getId();
    }

    private void sendDelay(String no, String kind, long seconds, Long insuranceId) {
        sendDelay(no, kind, seconds, insuranceId, null);
    }

    /**
     * R4-25：roundKey 非空时，延时消息的确定性 eventId 与 outbox bizKey 都带轮次维度
     * （见 {@link AftersaleTimeoutMessage}），保证同一售后单第二次进入同一状态时延时行
     * 可独立登记、独立投递、独立幂等，不与首轮行撞 uk_topic_tag_bizkey。
     */
    private void sendDelay(String no, String kind, long seconds, Long insuranceId, String roundKey) {
        // P2-1：工厂赋确定性 eventId；MQ 与 60s 扫表双路径据此共享同一幂等键
        AftersaleTimeoutMessage msg = insuranceId != null
                ? AftersaleTimeoutMessage.forInsurance(insuranceId, no, kind)
                : AftersaleTimeoutMessage.forAftersale(no, kind, roundKey);
        // P1-1：所有调用方均为 @Transactional 入口方法，延时事件与售后状态变更同事务
        outboxPublisher.publishDelay(AftersaleDelayTopics.AFTERSALE_TIMEOUT, kind, msg,
                AftersaleTimeoutMessage.outboxBizKey(no, roundKey), seconds);
    }

    private void returnStock(AftersaleOrder o) {
        List<StockItemCommand> cmds = itemMapper.selectByAftersaleNo(o.getAftersaleNo()).stream()
                .map(i -> StockItemCommand.builder().skuId(i.getSkuId()).qty(i.getQty()).build())
                .toList();
        if (cmds.isEmpty()) {
            return;
        }
        StockReturnCommand cmd = StockReturnCommand.builder()
                .orderNo(o.getOrderNo()).items(cmds).build();
        Result<Void> r = productClient.returnStock(cmd);
        // null 必须按失败处理：否则退货商品不回库存且调用方事务照常提交。
        // product 侧按 (order_no,sku_id,type) UK 幂等，MQ 重试/人工重放安全。
        if (r == null || !r.isSuccess()) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                    "退货入库失败: " + (r == null ? "下游返回空响应" : r.getMessage()));
        }
    }

    private void releaseRefs(String no) {
        for (AftersaleItem it : itemMapper.selectByAftersaleNo(no)) {
            itemRefMapper.release(it.getOrderItemId(), no);
        }
    }

    private void occupy(OrderItemRef ref, String no) {
        if (itemRefMapper.occupy(ref.getOrderItemId(), no) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该明细已有进行中售后");
        }
    }

    private OrderItemRef getOrCreateRef(AftersaleWindow window, OrderItemDTO di) {
        OrderItemRef ref = itemRefMapper.selectByOrderItemId(di.getOrderItemId());
        if (ref != null) {
            return ref;
        }
        ref = new OrderItemRef();
        ref.setOrderNo(di.getOrderNo());
        ref.setOrderItemId(di.getOrderItemId());
        ref.setUserId(window.getUserId());
        ref.setSkuId(di.getSkuId());
        ref.setSpuId(di.getSpuId());
        ref.setQty(di.getQty());
        ref.setPaidFen(nz(di.getPaidFen()));
        ref.setRefundedFen(0L);
        ref.setActiveNo("");
        ref.setWarrantyDays(15);
        itemRefMapper.insert(ref);
        return ref;
    }

    private AftersaleItem buildItem(String no, OrderItemDTO di, OrderItemRef ref, int qty, long refundFen) {
        AftersaleItem ai = new AftersaleItem();
        ai.setAftersaleNo(no);
        ai.setOrderItemId(di.getOrderItemId());
        ai.setSkuId(di.getSkuId());
        ai.setSpuId(di.getSpuId());
        ai.setProductName(di.getSkuName());
        ai.setSkuSpec(di.getSpecText());
        ai.setQty(qty);
        ai.setPaidFen(ref.getPaidFen() - ref.getRefundedFen());
        ai.setRefundFen(refundFen);
        return ai;
    }

    /**
     * 合并事件投影窗口与订单实时数据。ORDER_SHIPPED/ORDER_CONFIRMED 经 MQ 最终一致投递，
     * 投影可能滞后于订单实时状态（如刚确认收货、CONFIRMED 投影尚未落地时，投影行没有收货时间与
     * 售后期截止时间）。申请时限以订单实时状态/时间为准（design 8.3），投影缺失的发货、收货起点
     * 与窗口期由订单实时字段按 {@link AftersalePolicy} 同一规则补齐——校验强度不变，
     * 窗口期起点仍为真实收货时间，CONFIRMED 投影落地后按相同值幂等覆盖。
     */
    private AftersaleWindow materializeWindow(OrderDTO order, AftersaleWindow w) {
        if (w == null) {
            w = snapshotWindow(order);
        }
        w.setOrderStatus(order.getStatus());
        if (order.getOrderType() != null) {
            w.setOrderType(order.getOrderType());
        }
        if (w.getShippedTime() == null && order.getShipTime() != null) {
            w.setShippedTime(order.getShipTime());
        }
        if (order.getStatus() == 40 && order.getConfirmTime() != null) {
            LocalDateTime confirm = order.getConfirmTime();
            if (w.getConfirmTime() == null) {
                w.setConfirmTime(confirm);
            }
            if (w.getFreeAftersaleDeadline() == null) {
                w.setFreeAftersaleDeadline(policy.freeAftersaleDeadline(confirm));
            }
            if (w.getWarrantyDeadline() == null) {
                int warrantyDays = w.getWarrantyDays() != null ? w.getWarrantyDays()
                        : AftersaleCodes.FREE_AFTERSALE_DAYS;
                w.setWarrantyDeadline(policy.warrantyDeadline(confirm, warrantyDays));
            }
        }
        return w;
    }

    private AftersaleWindow snapshotWindow(OrderDTO order) {
        AftersaleWindow w = new AftersaleWindow();
        w.setOrderNo(order.getOrderNo());
        w.setUserId(order.getUserId());
        w.setMerchantId(merchantOf(order));
        w.setOrderStatus(order.getStatus());
        w.setOrderType(order.getOrderType());
        w.setProductPayFen(Math.max(0L, nz(order.getPayFen()) - nz(order.getFreightFen())));
        w.setFreightFen(nz(order.getFreightFen()));
        w.setUsedPointsFen(nz(order.getPointsDeductFen()));
        w.setUsedPoints(null);
        w.setHasFreightInsurance(0);
        w.setWarrantyDays(AftersaleCodes.FREE_AFTERSALE_DAYS);
        return w;
    }

    private boolean isFullRefund(OrderDTO order, long thisRefund) {
        long refunded = itemRefMapper.selectList(new LambdaQueryWrapper<OrderItemRef>()
                        .eq(OrderItemRef::getOrderNo, order.getOrderNo())).stream()
                .mapToLong(r -> nz(r.getRefundedFen())).sum();
        return refunded + thisRefund >= nz(order.getPayFen()) && nz(order.getPayFen()) > 0;
    }

    private Long firstSku(String no) {
        return itemMapper.selectByAftersaleNo(no).stream().findFirst()
                .map(AftersaleItem::getSkuId).orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "缺少换货SKU"));
    }

    private OrderDTO loadOrder(String orderNo) {
        return FeignResults.unwrap(orderClient.getByOrderNo(orderNo));
    }

    private AftersaleOrder requireAftersale(String no) {
        AftersaleOrder o = orderMapper.selectByNo(no);
        if (o == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "售后单不存在");
        }
        return o;
    }

    private void requireOwner(AftersaleOrder o, Long userId) {
        if (!userId.equals(o.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该售后单");
        }
    }

    private void requireMerchant(AftersaleOrder o, Long merchantId) {
        if (merchantId == null || !merchantId.equals(o.getMerchantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅所属商户可操作");
        }
    }

    private long merchantOf(OrderDTO order) {
        return order.getItems().stream().findFirst().map(OrderItemDTO::getMerchantId).orElse(0L);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nzInt(Integer v) {
        return v == null ? 0 : v;
    }
}
