package com.shop.settlement.clearing.service;

import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderItemDTO;
import com.shop.api.order.event.OrderConfirmedEvent;
import com.shop.api.order.event.OrderCompletedEvent;
import com.shop.api.pay.enums.PayScenes;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.settlement.enums.ClearingStages;
import com.shop.api.settlement.enums.MerchantLevels;
import com.shop.common.exception.BizException;
import com.shop.common.result.Result;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.mapper.ClearingMapper;
import com.shop.settlement.engine.SettleCycle;
import com.shop.settlement.engine.SplitEngine;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.mq.service.MqConsumeService;
import com.shop.settlement.support.LambdaTableSupport;
import com.shop.settlement.support.SettleNoGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清算单事件处理单测：ORDER_PAID 登记 / ORDER_CONFIRMED 补全 stage=20+due_date /
 * ORDER_COMPLETED B 级兜底 / MQ 重复消费。
 */
@ExtendWith(MockitoExtension.class)
class ClearingServiceTest {

    @Mock private ClearingMapper clearingMapper;
    @Mock private MerchantService merchantService;
    @Mock private MqConsumeService mqConsumeService;
    @Mock private SettleNoGenerator noGenerator;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private OrderClient orderClient;

    private final SplitEngine splitEngine = new SplitEngine();
    private final SettleCycle settleCycle = new SettleCycle();

    private ClearingService service;

    @BeforeAll
    static void initLambdaCache() {
        LambdaTableSupport.init();
    }

    @BeforeEach
    void setUp() {
        service = new ClearingService(clearingMapper, merchantService, mqConsumeService,
                noGenerator, splitEngine, settleCycle, outboxPublisher, orderClient);
    }

    private SettMerchant merchant(int level, int rateBps) {
        SettMerchant m = new SettMerchant();
        m.setId(777L);
        m.setMerchantLevel(level);
        m.setCommissionRateBps(rateBps);
        m.setDepositBalanceFen(500_000L);
        m.setDepositRequiredFen(200_000L);
        m.setStatus(1);
        return m;
    }

    @Test
    @DisplayName("paid_首次消费_回查订单登记stage10并发登记事件")
    void onPaid_firstTime_registerStage10() {
        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .payNo("P001").orderNo("O2026091601").userId(9L).amountFen(17_700L).build();
        when(mqConsumeService.tryRecord(any(), any(), eq(ClearingService.CG_PAID), eq("O2026091601")))
                .thenReturn(true);
        when(clearingMapper.selectOne(any())).thenReturn(null);
        OrderDTO order = OrderDTO.builder()
                .orderNo("O2026091601").userId(9L)
                .productTotalFen(20_000L).freightFen(1_200L).shopDiscountFen(2_000L)
                .platformDiscountFen(1_000L).pointsDeductFen(500L)
                .items(List.of(OrderItemDTO.builder().merchantId(777L).build()))
                .build();
        when(orderClient.getByOrderNo("O2026091601")).thenReturn(Result.success(order));
        when(merchantService.requireMerchant(777L)).thenReturn(merchant(MerchantLevels.B, 500));
        when(noGenerator.nextClearingNo()).thenReturn("CL2609160001");

        service.onPaymentSucceeded(event);

        ArgumentCaptor<SettClearing> cap = ArgumentCaptor.forClass(SettClearing.class);
        verify(clearingMapper).insert(cap.capture());
        SettClearing c = cap.getValue();
        assertEquals(ClearingStages.WAIT_CLEAR, c.getStage());
        assertEquals(777L, c.getMerchantId());
        assertEquals(17_700L, c.getPayAmountFen());
        assertEquals(18_130L, c.getMerchantReceivableFen());
        assertEquals(900L, c.getPlatformCommissionFen());
        assertEquals(120L, c.getChannelFeeFen());
        assertEquals(1_500L, c.getMarketingSubsidyFen());
        // P1-1：清算登记事件在同一 @Transactional 内登记 outbox（mock 验证调用点与 bizKey）
        verify(outboxPublisher).publish(eq(com.shop.common.constant.MqTopics.CLEARING_REGISTER),
                any(), any(), eq("O2026091601"));
    }

    @Test
    @DisplayName("paid_重复eventId_直接ACK不登记")
    void onPaid_duplicateEvent_skip() {
        PaymentSucceededEvent event = PaymentSucceededEvent.builder().orderNo("O1").build();
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(false);

        service.onPaymentSucceeded(event);

        verify(clearingMapper, never()).insert(any());
        verify(orderClient, never()).getByOrderNo(any());
    }

    @Test
    @DisplayName("B10_paid_payScene4保证金缴费_消费记录照写但直接ACK不查单不建清算单")
    void onPaid_depositScene_ackAfterConsumeRecord() {
        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .payNo("DP001").orderNo("DP2026091601").userId(9L)
                .amountFen(200_000L).payScene(PayScenes.DEPOSIT).build();
        when(mqConsumeService.tryRecord(any(), any(), eq(ClearingService.CG_PAID), eq("DP2026091601")))
                .thenReturn(true);

        service.onPaymentSucceeded(event);

        // 消费记录先落库（挡住重投）
        verify(mqConsumeService).tryRecord(any(), any(), eq(ClearingService.CG_PAID), eq("DP2026091601"));
        // 业务全部跳过：不查清算单、不回查 OrderClient、不登记、不发事件
        verify(clearingMapper, never()).selectOne(any());
        verify(clearingMapper, never()).insert(any());
        verify(orderClient, never()).getByOrderNo(any());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("B11_paid_OrderDTO含运费险保费_清算单落insurance_premium_fen且payAmount含保费")
    void onPaid_withInsurancePremium_persistColumnAndPayAmount() {
        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .payNo("P002").orderNo("O2026091602").userId(9L).amountFen(17_800L).build();
        when(mqConsumeService.tryRecord(any(), any(), eq(ClearingService.CG_PAID), eq("O2026091602")))
                .thenReturn(true);
        when(clearingMapper.selectOne(any())).thenReturn(null);
        OrderDTO order = OrderDTO.builder()
                .orderNo("O2026091602").userId(9L)
                .productTotalFen(20_000L).freightFen(1_200L).shopDiscountFen(2_000L)
                .platformDiscountFen(1_000L).pointsDeductFen(500L)
                .insurancePremiumFen(100L)
                .items(List.of(OrderItemDTO.builder().merchantId(777L).build()))
                .build();
        when(orderClient.getByOrderNo("O2026091602")).thenReturn(Result.success(order));
        when(merchantService.requireMerchant(777L)).thenReturn(merchant(MerchantLevels.B, 500));
        when(noGenerator.nextClearingNo()).thenReturn("CL2609160002");

        service.onPaymentSucceeded(event);

        ArgumentCaptor<SettClearing> cap = ArgumentCaptor.forClass(SettClearing.class);
        verify(clearingMapper).insert(cap.capture());
        SettClearing c = cap.getValue();
        // 保费列落库，payAmountFen=支付事件金额（含保费）
        assertEquals(100L, c.getInsurancePremiumFen());
        assertEquals(17_800L, c.getPayAmountFen());
        // 商品侧分账不受保费影响
        assertEquals(18_130L, c.getMerchantReceivableFen());
        assertEquals(900L, c.getPlatformCommissionFen());
        assertEquals(120L, c.getChannelFeeFen());
        assertEquals(50L, c.getTechFeeFen());
        assertEquals(1_500L, c.getMarketingSubsidyFen());
        // 自洽：payAmount(含保费) + 营销补贴 = 应收+佣金+通道费+技服费+保费
        assertEquals(c.getPayAmountFen() + c.getMarketingSubsidyFen(),
                c.getMerchantReceivableFen() + c.getPlatformCommissionFen()
                        + c.getChannelFeeFen() + c.getTechFeeFen()
                        + c.getInsurancePremiumFen());
    }

    @Test
    @DisplayName("paid_回查订单失败_抛依赖异常触发重试")
    void onPaid_orderQueryFail_throw() {        PaymentSucceededEvent event = PaymentSucceededEvent.builder().orderNo("O1").amountFen(100L).build();
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(clearingMapper.selectOne(any())).thenReturn(null);
        when(orderClient.getByOrderNo("O1")).thenReturn(null);

        assertThrows(BizException.class, () -> service.onPaymentSucceeded(event));
    }

    @Test
    @DisplayName("confirmed_首次_重算分账落stage20与B级T15到期日")
    void onConfirmed_firstTime_recomputeAndDueDate() {
        OrderConfirmedEvent event = OrderConfirmedEvent.builder()
                .orderNo("O1").userId(9L).merchantId(777L)
                .productPayFen(16_500L).freightFen(1_200L).shopDiscountFen(2_000L)
                .platformCouponFen(1_000L).pointsDeductFen(500L).totalPayFen(17_700L)
                .build();
        when(mqConsumeService.tryRecord(any(), any(), eq(ClearingService.CG_CONFIRMED), eq("O1")))
                .thenReturn(true);
        SettClearing existed = new SettClearing();
        existed.setId(1L);
        existed.setOrderNo("O1");
        existed.setMerchantId(777L);
        existed.setStage(ClearingStages.WAIT_CLEAR);
        when(clearingMapper.selectOne(any())).thenReturn(existed);
        when(merchantService.requireMerchant(777L)).thenReturn(merchant(MerchantLevels.B, 500));
        when(clearingMapper.update(any(), any())).thenReturn(1);

        service.onOrderConfirmed(event);

        ArgumentCaptor<SettClearing> cap = ArgumentCaptor.forClass(SettClearing.class);
        verify(clearingMapper).update(cap.capture(), any());
        SettClearing patch = cap.getValue();
        assertEquals(ClearingStages.WAIT_SETTLE, patch.getStage());
        // 优惠前商品额 = 16500 + 2000 + 1000 + 500 = 20000
        assertEquals(20_000L, patch.getProductAmountFen());
        assertEquals(18_130L, patch.getMerchantReceivableFen());
        assertEquals(LocalDate.now().plusDays(15), patch.getDueDate());
    }

    @Test
    @DisplayName("confirmed_清算单不存在_抛异常等重试")
    void onConfirmed_missingClearing_throw() {
        OrderConfirmedEvent event = OrderConfirmedEvent.builder().orderNo("O1").build();
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(clearingMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.onOrderConfirmed(event));
    }

    @Test
    @DisplayName("confirmed_重复消费stage已20_幂等跳过")
    void onConfirmed_alreadyStage20_skip() {
        OrderConfirmedEvent event = OrderConfirmedEvent.builder().orderNo("O1").build();
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        SettClearing existed = new SettClearing();
        existed.setId(1L);
        existed.setStage(ClearingStages.WAIT_SETTLE);
        when(clearingMapper.selectOne(any())).thenReturn(existed);

        service.onOrderConfirmed(event);
        verify(clearingMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("completed_B级stage20且dueDate在未来_提前到今日")
    void onCompleted_bLevel_advanceDueDate() {
        OrderCompletedEvent event = OrderCompletedEvent.builder()
                .orderNo("O1").merchantId(777L).build();
        when(mqConsumeService.tryRecord(any(), any(), eq(ClearingService.CG_COMPLETED), eq("O1")))
                .thenReturn(true);
        SettClearing existed = new SettClearing();
        existed.setId(1L);
        existed.setMerchantId(777L);
        existed.setStage(ClearingStages.WAIT_SETTLE);
        existed.setDueDate(LocalDate.now().plusDays(3));
        when(clearingMapper.selectOne(any())).thenReturn(existed);
        when(merchantService.requireMerchant(777L)).thenReturn(merchant(MerchantLevels.B, 500));
        when(clearingMapper.update(any(), any())).thenReturn(1);

        service.onOrderCompleted(event);
        verify(clearingMapper).update(any(), any());
    }

    @Test
    @DisplayName("completed_A级_不调整dueDate")
    void onCompleted_aLevel_noop() {
        OrderCompletedEvent event = OrderCompletedEvent.builder()
                .orderNo("O1").merchantId(777L).build();
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        SettClearing existed = new SettClearing();
        existed.setId(1L);
        existed.setMerchantId(777L);
        existed.setStage(ClearingStages.WAIT_SETTLE);
        existed.setDueDate(LocalDate.now().plusDays(3));
        when(clearingMapper.selectOne(any())).thenReturn(existed);
        when(merchantService.requireMerchant(777L)).thenReturn(merchant(MerchantLevels.A, 500));

        service.onOrderCompleted(event);
        verify(clearingMapper, never()).update(any(), any());
    }
}
