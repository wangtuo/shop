package com.shop.settlement.clearing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderItemDTO;
import com.shop.api.order.event.OrderConfirmedEvent;
import com.shop.api.order.event.OrderCompletedEvent;
import com.shop.api.pay.enums.PayScenes;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.settlement.dto.ClearingBreakdown;
import com.shop.api.settlement.enums.ClearingStages;
import com.shop.api.settlement.enums.MerchantLevels;
import com.shop.api.settlement.event.ClearingRegisteredEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.mapper.ClearingMapper;
import com.shop.settlement.engine.SettleCycle;
import com.shop.settlement.engine.SplitEngine;
import com.shop.settlement.engine.SplitRequest;
import com.shop.settlement.engine.SplitResult;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.mq.service.MqConsumeService;
import com.shop.settlement.support.SettleNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 清算单服务（design 7.3）：
 * ORDER_PAID 登记 stage=10 待清算；ORDER_CONFIRMED 补全/重算分账 stage=20 待结算并按等级落 due_date；
 * ORDER_COMPLETED 做 B 级售后期结束转结算的兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClearingService {

    public static final String CG_PAID = "cg_sett_paid";
    public static final String CG_CONFIRMED = "cg_sett_confirmed";
    public static final String CG_COMPLETED = "cg_sett_completed";

    private final ClearingMapper clearingMapper;
    private final MerchantService merchantService;
    private final MqConsumeService mqConsumeService;
    private final SettleNoGenerator noGenerator;
    private final SplitEngine splitEngine;
    private final SettleCycle settleCycle;
    // P1-1：清算登记事件在 onPaymentSucceeded 事务内登记 outbox，与清算单同提交/回滚
    private final OutboxPublisher outboxPublisher;
    private final OrderClient orderClient;

    /**
     * 消费 ORDER_PAID：登记待清算单 stage=10。
     * PaymentSucceededEvent 仅含 orderNo/amountFen 等（无商户ID与优惠明细，契约缺口见报告），
     * 通过 OrderClient 按 orderNo 回查订单补全；回查失败抛异常由 Broker 重试。
     */
    @Transactional
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        if (!mqConsumeService.tryRecord(event.getEventId(), MqTopics.ORDER_PAID, CG_PAID, event.getOrderNo())) {
            log.info("ORDER_PAID 重复消费直接ACK eventId={} orderNo={}", event.getEventId(), event.getOrderNo());
            return;
        }
        // B10：保证金缴费（payScene=4）复用 ORDER_PAID 链路，清算域不建清算单、不回查订单。
        // 必须在消费记录落库之后再 return（重投由 mq_consume 幂等挡住），业务处理（查单/登记）之前。
        if (event.getPayScene() != null && event.getPayScene() == PayScenes.DEPOSIT) {
            log.info("保证金缴费支付事件 payScene=4，清算域直接ACK不落清算单 eventId={} payNo={}",
                    event.getEventId(), event.getPayNo());
            return;
        }
        SettClearing existed = getByOrderNo(event.getOrderNo());
        if (existed != null) {
            log.info("清算单已存在，跳过登记 orderNo={}", event.getOrderNo());
            return;
        }

        OrderDTO order = queryOrder(event.getOrderNo());
        Long merchantId = pickMerchantId(order);
        SettMerchant merchant = merchantService.requireMerchant(merchantId);

        SplitRequest req = SplitRequest.builder()
                .productAmountFen(nz(order.getProductTotalFen()))
                .freightFen(nz(order.getFreightFen()))
                .merchantBearDiscountFen(nz(order.getShopDiscountFen()))
                .platformBearDiscountFen(nz(order.getPlatformDiscountFen()) + nz(order.getPointsDeductFen()))
                .insurancePremiumFen(nz(order.getInsurancePremiumFen()))
                .commissionRateBps(merchant.getCommissionRateBps())
                .build();
        SplitResult split = splitEngine.split(req);

        SettClearing clearing = new SettClearing();
        clearing.setClearingNo(noGenerator.nextClearingNo());
        clearing.setOrderNo(event.getOrderNo());
        clearing.setPayNo(event.getPayNo() == null ? "" : event.getPayNo());
        clearing.setMerchantId(merchantId);
        clearing.setUserId(event.getUserId() == null ? nz(order.getUserId()) : event.getUserId());
        clearing.setStage(ClearingStages.WAIT_CLEAR);
        applySplit(clearing, split);
        // 支付实付以支付事件为准
        clearing.setPayAmountFen(nz(event.getAmountFen()));
        clearing.setReversedMerchantFen(0L);
        clearing.setReversedCommissionFen(0L);
        clearing.setReversedSubsidyFen(0L);
        clearing.setRefundedFen(0L);
        clearingMapper.insert(clearing);

        publishRegistered(clearing);
        log.info("清算单登记完成 stage=10 clearingNo={} orderNo={} merchantId={}",
                clearing.getClearingNo(), event.getOrderNo(), merchantId);
    }

    /**
     * 消费 ORDER_CONFIRMED：按事件完整金额重算分账，stage=20 并落 due_date。
     */
    @Transactional
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        if (!mqConsumeService.tryRecord(event.getEventId(), MqTopics.ORDER_CONFIRMED,
                CG_CONFIRMED, event.getOrderNo())) {
            log.info("ORDER_CONFIRMED 重复消费直接ACK eventId={} orderNo={}", event.getEventId(), event.getOrderNo());
            return;
        }
        SettClearing clearing = getByOrderNo(event.getOrderNo());
        if (clearing == null) {
            // 支付事件尚未处理完成，抛出由 Broker 重试
            throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                    "清算单尚未登记，等待ORDER_PAID处理: " + event.getOrderNo());
        }
        if (clearing.getStage() != null && clearing.getStage() >= ClearingStages.WAIT_SETTLE) {
            log.info("清算单已过待清算阶段，幂等跳过 orderNo={} stage={}",
                    event.getOrderNo(), clearing.getStage());
            return;
        }
        Long merchantId = event.getMerchantId() != null ? event.getMerchantId() : clearing.getMerchantId();
        SettMerchant merchant = merchantService.requireMerchant(merchantId);

        // OrderConfirmedEvent 无「优惠前商品总额」字段：以 商品实付 + 店铺优惠 + 平台券 + 积分抵现 还原
        long productAmount = nz(event.getProductPayFen())
                + nz(event.getShopDiscountFen())
                + nz(event.getPlatformCouponFen())
                + nz(event.getPointsDeductFen());
        SplitRequest req = SplitRequest.builder()
                .productAmountFen(productAmount)
                .freightFen(nz(event.getFreightFen()))
                .merchantBearDiscountFen(nz(event.getShopDiscountFen()))
                .platformBearDiscountFen(nz(event.getPlatformCouponFen()) + nz(event.getPointsDeductFen()))
                .insurancePremiumFen(nz(event.getInsurancePremiumFen()))
                .commissionRateBps(merchant.getCommissionRateBps())
                .build();
        SplitResult split = splitEngine.split(req);
        LocalDate confirmedDate = LocalDate.now();
        LocalDate dueDate = settleCycle.dueDate(merchant.getMerchantLevel(), confirmedDate);

        SettClearing patch = new SettClearing();
        patch.setMerchantId(merchantId);
        applySplit(patch, split);
        if (event.getUserId() != null) {
            patch.setUserId(event.getUserId());
        }
        patch.setPayAmountFen(nz(event.getTotalPayFen()));
        patch.setStage(ClearingStages.WAIT_SETTLE);
        patch.setConfirmedTime(LocalDateTime.now());
        patch.setDueDate(dueDate);
        int rows = clearingMapper.update(patch, new LambdaUpdateWrapper<SettClearing>()
                .eq(SettClearing::getId, clearing.getId())
                .eq(SettClearing::getStage, ClearingStages.WAIT_CLEAR));
        if (rows == 0) {
            log.warn("清算单并发冲突，确认收货幂等跳过 orderNo={}", event.getOrderNo());
            return;
        }
        log.info("清算单分账完成 stage=20 orderNo={} dueDate={} 商户应收={}",
                event.getOrderNo(), dueDate, split.getMerchantReceivableFen());
    }

    /**
     * 消费 ORDER_COMPLETED：B 级商户售后期结束转结算的兜底——将 due_date 提前到今日，
     * 由当日日终批扫描转 stage=30。S/A 通常已到期结算；C 级仍保持 T+30。
     */
    @Transactional
    public void onOrderCompleted(OrderCompletedEvent event) {
        if (!mqConsumeService.tryRecord(event.getEventId(), MqTopics.ORDER_COMPLETED,
                CG_COMPLETED, event.getOrderNo())) {
            log.info("ORDER_COMPLETED 重复消费直接ACK eventId={} orderNo={}", event.getEventId(), event.getOrderNo());
            return;
        }
        SettClearing clearing = getByOrderNo(event.getOrderNo());
        if (clearing == null) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                    "清算单尚未登记: " + event.getOrderNo());
        }
        if (clearing.getStage() == null || clearing.getStage() != ClearingStages.WAIT_SETTLE) {
            return;
        }
        SettMerchant merchant = merchantService.requireMerchant(clearing.getMerchantId());
        if (merchant.getMerchantLevel() != MerchantLevels.B) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (clearing.getDueDate() != null && !clearing.getDueDate().isAfter(today)) {
            return;
        }
        int rows = clearingMapper.update(null, new LambdaUpdateWrapper<SettClearing>()
                .eq(SettClearing::getId, clearing.getId())
                .eq(SettClearing::getStage, ClearingStages.WAIT_SETTLE)
                .set(SettClearing::getDueDate, today));
        if (rows == 1) {
            log.info("B级商户售后期结束兜底，dueDate提前至今日 orderNo={}", event.getOrderNo());
        }
    }

    /** 商户端分页查询本店清算单（归属鉴权在 Controller 完成 merchantId 注入）。 */
    public PageResult<SettClearing> pageMerchant(long merchantId, int pageNum, int pageSize) {
        Page<SettClearing> page = new Page<>(pageNum, pageSize);
        Page<SettClearing> result = clearingMapper.selectPage(page, new LambdaQueryWrapper<SettClearing>()
                .eq(SettClearing::getMerchantId, merchantId)
                .orderByDesc(SettClearing::getId));
        return PageResult.of(pageNum, pageSize, result.getTotal(), result.getRecords());
    }

    public SettClearing getByOrderNo(String orderNo) {
        return clearingMapper.selectOne(new LambdaQueryWrapper<SettClearing>()
                .eq(SettClearing::getOrderNo, orderNo));
    }

    public List<SettClearing> selectDuePage(long lastId, LocalDate today, int limit) {
        return clearingMapper.selectDuePage(lastId, today, limit);
    }

    /** 条件推进阶段（日终批 20→30、退款 →40），影响 0 行即并发冲突。 */
    public int compareAndUpdateStage(long id, int expectStage, int targetStage,
                                     java.util.function.Consumer<LambdaUpdateWrapper<SettClearing>> customizer) {
        LambdaUpdateWrapper<SettClearing> wrapper = new LambdaUpdateWrapper<SettClearing>()
                .eq(SettClearing::getId, id)
                .eq(SettClearing::getStage, expectStage);
        wrapper.set(SettClearing::getStage, targetStage);
        if (customizer != null) {
            customizer.accept(wrapper);
        }
        return clearingMapper.update(null, wrapper);
    }

    private void publishRegistered(SettClearing c) {
        ClearingBreakdown breakdown = ClearingBreakdown.builder()
                .clearingNo(c.getClearingNo())
                .orderNo(c.getOrderNo())
                .merchantId(c.getMerchantId())
                .productAmountFen(c.getProductAmountFen())
                .merchantBearDiscountFen(c.getMerchantBearDiscountFen())
                .platformBearDiscountFen(c.getPlatformBearDiscountFen())
                .merchantReceivableFen(c.getMerchantReceivableFen())
                .platformCommissionFen(c.getPlatformCommissionFen())
                .techFeeFen(c.getTechFeeFen())
                .channelFeeFen(c.getChannelFeeFen())
                .marketingSubsidyFen(c.getMarketingSubsidyFen())
                .insurancePremiumFen(c.getInsurancePremiumFen())
                .freightFen(c.getFreightFen())
                .commissionRateBps(c.getCommissionRateBps())
                .stage(ClearingStages.WAIT_CLEAR)
                .build();
        ClearingRegisteredEvent event = ClearingRegisteredEvent.builder()
                .breakdown(breakdown)
                .orderNo(c.getOrderNo())
                .merchantId(c.getMerchantId())
                .build();
        event.setBizNo(c.getOrderNo());
        // P1-1：调用方 onPaymentSucceeded 处于 @Transactional 内，事件随清算单一并提交
        outboxPublisher.publish(MqTopics.CLEARING_REGISTER, null, event, c.getOrderNo());
    }

    private OrderDTO queryOrder(String orderNo) {
        Result<OrderDTO> result;
        try {
            result = orderClient.getByOrderNo(orderNo);
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL, "回查订单失败，等待重试: " + orderNo, e);
        }
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL, "回查订单不存在或失败: " + orderNo);
        }
        return result.getData();
    }

    private Long pickMerchantId(OrderDTO order) {
        if (order.getItems() != null) {
            for (OrderItemDTO item : order.getItems()) {
                if (item.getMerchantId() != null) {
                    return item.getMerchantId();
                }
            }
        }
        throw new BizException(ErrorCode.DEPENDENCY_FAIL, "订单缺少商户信息: " + order.getOrderNo());
    }

    private void applySplit(SettClearing c, SplitResult s) {
        c.setProductAmountFen(s.getProductAmountFen());
        c.setFreightFen(s.getFreightFen());
        c.setMerchantBearDiscountFen(s.getMerchantBearDiscountFen());
        c.setPlatformBearDiscountFen(s.getPlatformBearDiscountFen());
        c.setMerchantReceivableFen(s.getMerchantReceivableFen());
        c.setPlatformCommissionFen(s.getPlatformCommissionFen());
        c.setTechFeeFen(s.getTechFeeFen());
        c.setChannelFeeFen(s.getChannelFeeFen());
        c.setMarketingSubsidyFen(s.getMarketingSubsidyFen());
        c.setInsurancePremiumFen(s.getInsurancePremiumFen());
        c.setCommissionRateBps(s.getCommissionRateBps());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
