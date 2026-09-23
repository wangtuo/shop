package com.shop.aftersale.aftersale.service;

import com.shop.aftersale.aftersale.dto.AftersaleApplyRequest;
import com.shop.aftersale.aftersale.dto.ArbitrateRequest;
import com.shop.aftersale.aftersale.dto.LogisticsRequest;
import com.shop.aftersale.aftersale.dto.PriceProtectTrialRequest;
import com.shop.aftersale.aftersale.dto.PriceProtectTrialVO;
import com.shop.aftersale.aftersale.entity.AftersaleDispute;
import com.shop.aftersale.aftersale.entity.AftersaleItem;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.aftersale.aftersale.entity.AftersaleRefund;
import com.shop.aftersale.aftersale.entity.AftersaleWindow;
import com.shop.aftersale.aftersale.entity.OrderItemRef;
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
import com.shop.aftersale.aftersale.service.impl.AftersaleServiceImpl;
import com.shop.aftersale.idgen.AftersaleNoGenerator;
import com.shop.aftersale.statemachine.AftersaleStateMachine;
import com.shop.aftersale.support.AftersaleEventPublisher;
import com.shop.aftersale.support.AftersalePolicy;
import com.shop.aftersale.support.AftersaleRefundStore;
import com.shop.aftersale.support.RefundCalculator;
import com.shop.api.aftersale.enums.AftersaleStatuses;
import com.shop.api.aftersale.enums.AftersaleTypes;
import com.shop.api.aftersale.enums.ArbitrationResults;
import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderItemDTO;
import com.shop.api.pay.client.PayClient;
import com.shop.api.pay.dto.CreateRefundCommand;
import com.shop.api.pay.dto.RefundDTO;
import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.SkuDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.framework.feign.FeignResults;
import com.shop.aftersale.support.FeignFailures;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

/**
 * 售后主流程：五类型关键路径、退款金额、超时自动流转、价保、平台仲裁。
 */
@ExtendWith(MockitoExtension.class)
class AftersaleServiceImplTest {

    @Mock private AftersaleOrderMapper orderMapper;
    @Mock private AftersaleItemMapper itemMapper;
    @Mock private AftersaleWindowMapper windowMapper;
    @Mock private OrderItemRefMapper itemRefMapper;
    @Mock private AftersaleRefundMapper refundMapper;
    @Mock private AftersaleDisputeMapper disputeMapper;
    @Mock private AftersaleEvidenceMapper evidenceMapper;
    @Mock private PriceProtectRecordMapper priceProtectMapper;
    @Mock private StatusLogMapper statusLogMapper;
    @Mock private OrderClient orderClient;
    @Mock private PayClient payClient;
    @Mock private ProductClient productClient;
    @Mock private AftersaleEventPublisher eventPublisher;
    @Mock private AftersaleNoGenerator noGenerator;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private AftersaleRefundStore refundStore;

    private AftersaleServiceImpl service;

    private static final long USER = 1001L;
    private static final long MERCHANT = 2002L;
    /** O5：仲裁测试默认平台运营真实 ID（必须落入状态日志，不得为 0） */
    private static final long PLATFORM_ADMIN = 1L;

    @BeforeEach
    void setUp() {
        service = new AftersaleServiceImpl(orderMapper, itemMapper, windowMapper, itemRefMapper,
                refundMapper, disputeMapper, evidenceMapper, priceProtectMapper, statusLogMapper,
                orderClient, payClient, productClient,
                new AftersaleStateMachine(), new AftersalePolicy(), new RefundCalculator(),
                eventPublisher, noGenerator, outboxPublisher, refundStore);
        // 模拟 MyBatis-Plus 雪花 ID 回填：R4-25 后状态流转 outbox 键 aftersaleNo#t{statusLogId}，
        // 每次流转需要唯一且非空的流水 id
        statusLogIdSeq.set(9001L);
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            com.shop.aftersale.aftersale.entity.StatusLog l = inv.getArgument(0);
            l.setId(statusLogIdSeq.getAndIncrement());
            return 1;
        }).when(statusLogMapper).insert(any());
        // 仲裁类测试默认以平台运营身份执行；鉴权拒绝用例单独覆盖上下文
        UserContext.set(LoginUser.builder().userId(1L).userType(2).build());
    }

    /** R4-25：模拟 t_aftersale_status_log 自增/雪花 ID 的顺序回填（每次状态流转唯一）。 */
    private final java.util.concurrent.atomic.AtomicLong statusLogIdSeq =
            new java.util.concurrent.atomic.AtomicLong(9001L);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------------- helpers ----------------

    private OrderItemDTO orderItem(long itemId, long skuId, int qty, long paidFen) {
        return OrderItemDTO.builder().orderItemId(itemId).skuId(skuId).spuId(skuId + 10)
                .merchantId(MERCHANT).skuName("商品").specText("红 XL").qty(qty)
                .paidFen(paidFen).build();
    }

    private OrderDTO order(int status, int orderType, long payFen, long freightFen, OrderItemDTO... items) {
        return OrderDTO.builder().orderNo("O202609160001").userId(USER).status(status)
                .orderType(orderType).payFen(payFen).freightFen(freightFen).pointsDeductFen(0L)
                .createTime(LocalDateTime.now().minusHours(1))
                .items(List.of(items)).build();
    }

    /** 已完成订单：带真实收货时间（OrderClient 实时视图），下单时间 1 小时前。 */
    private OrderDTO completedOrder(long payFen, OrderItemDTO... items) {
        OrderDTO o = order(40, 1, payFen, 0L, items);
        o.setShipTime(LocalDateTime.now().minusMinutes(30));
        o.setConfirmTime(LocalDateTime.now().minusMinutes(1));
        return o;
    }

    private void stubApplyItemRefs() {
        when(itemRefMapper.selectByOrderItemId(11L)).thenReturn(null);
        when(itemRefMapper.occupy(any(), any())).thenReturn(1);
        when(itemRefMapper.selectList(any())).thenReturn(List.of());
        when(noGenerator.nextAftersaleNo()).thenReturn("AS202609160000000009");
    }

    private AftersaleApplyRequest applyReq(int type, long itemId, int qty) {
        AftersaleApplyRequest req = new AftersaleApplyRequest();
        req.setOrderNo("O202609160001");
        req.setType(type);
        req.setResponsibilitySide(2);
        req.setReason("不喜欢");
        AftersaleApplyRequest.Item it = new AftersaleApplyRequest.Item();
        it.setOrderItemId(itemId);
        it.setQty(qty);
        req.setItems(List.of(it));
        return req;
    }

    private OrderItemRef itemRef(long paid, long refunded, String activeNo) {
        OrderItemRef r = new OrderItemRef();
        r.setOrderItemId(11L);
        r.setOrderNo("O202609160001");
        r.setUserId(USER);
        r.setSkuId(101L);
        r.setQty(2);
        r.setPaidFen(paid);
        r.setRefundedFen(refunded);
        r.setActiveNo(activeNo);
        return r;
    }

    private AftersaleOrder aftersale(int type, int status, long refundFen) {
        AftersaleOrder o = new AftersaleOrder();
        o.setAftersaleNo("AS202609160000000001");
        o.setOrderNo("O202609160001");
        o.setUserId(USER);
        o.setMerchantId(MERCHANT);
        o.setType(type);
        o.setStatus(status);
        o.setResponsibilitySide(2);
        o.setReason("原因");
        o.setRefundFen(refundFen);
        o.setRefundType(2);
        o.setPointsRefund(0);
        o.setApplyTime(LocalDateTime.now());
        return o;
    }

    private void stubRefundFlow() {
        when(refundMapper.selectByAftersaleNo(any())).thenReturn(null);
        when(noGenerator.nextRefundNo()).thenReturn("R20260916000000001");
        when(payClient.refund(any())).thenReturn(Result.success(RefundDTO.builder()
                .refundNo("R20260916000000001").payMethod(1).status(30).build()));
        when(refundMapper.updateStatus(any(), anyInt(), anyInt(), any(), any())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of());
    }

    /** 捕获本次调用写入的唯一一条状态日志（仲裁各分支均只产生一条）。 */
    private StatusLog captureSingleStatusLog() {
        ArgumentCaptor<StatusLog> cap = ArgumentCaptor.forClass(StatusLog.class);
        verify(statusLogMapper).insert(cap.capture());
        return cap.getValue();
    }

    // ---------------- 五类售后申请 ----------------

    @Test
    void apply_仅退款_退款金额剔除优惠券并占用明细() {
        OrderDTO order = order(20, 1, 11000L, 1000L, orderItem(11L, 101L, 2, 10000L));
        when(orderClient.getByOrderNo("O202609160001")).thenReturn(Result.success(order));
        when(windowMapper.selectByOrderNo(any())).thenReturn(null);
        when(itemRefMapper.selectByOrderItemId(11L)).thenReturn(null);
        when(itemRefMapper.occupy(eq(11L), any())).thenReturn(1);
        when(itemRefMapper.selectList(any())).thenReturn(List.of());
        when(noGenerator.nextAftersaleNo()).thenReturn("AS202609160000000001");

        String no = service.apply(applyReq(AftersaleTypes.REFUND_ONLY, 11L, 2), USER);

        assertEquals("AS202609160000000001", no);
        ArgumentCaptor<AftersaleOrder> cap = ArgumentCaptor.forClass(AftersaleOrder.class);
        verify(orderMapper).insert(cap.capture());
        assertEquals(10000L, cap.getValue().getRefundFen());
        assertEquals(AftersaleStatuses.WAIT_MERCHANT_AUDIT, cap.getValue().getStatus());
        verify(outboxPublisher).publishDelay(any(), any(), any(), any(), anyLong());
    }

    @Test
    void apply_可退余额为0_抛金额异常() {
        OrderDTO order = order(20, 1, 11000L, 1000L, orderItem(11L, 101L, 2, 10000L));
        when(orderClient.getByOrderNo(any())).thenReturn(Result.success(order));
        when(windowMapper.selectByOrderNo(any())).thenReturn(null);
        when(itemRefMapper.selectByOrderItemId(11L)).thenReturn(itemRef(10000L, 10000L, ""));

        BizException ex = assertThrows(BizException.class,
                () -> service.apply(applyReq(AftersaleTypes.REFUND_ONLY, 11L, 1), USER));
        assertEquals(80003, ex.getCode());
    }

    @Test
    void apply_明细已有进行中售后_冲突() {
        OrderDTO order = order(20, 1, 11000L, 1000L, orderItem(11L, 101L, 2, 10000L));
        when(orderClient.getByOrderNo(any())).thenReturn(Result.success(order));
        when(windowMapper.selectByOrderNo(any())).thenReturn(null);
        when(itemRefMapper.selectByOrderItemId(11L)).thenReturn(itemRef(10000L, 0L, "AS_OTHER"));

        assertThrows(BizException.class,
                () -> service.apply(applyReq(AftersaleTypes.REFUND_ONLY, 11L, 1), USER));
    }

    @Test
    void apply_已完成订单_CONFIRMED投影未落地_按实时收货时间补窗口期放行() {
        // 回归 E2E：刚确认收货时 ORDER_CONFIRMED 投影尚未消费，窗口行不存在，
        // 不得误判「已超出收货后15天售后期」——以订单实时 confirmTime 按 15 天补齐窗口
        OrderDTO order = completedOrder(7000L, orderItem(11L, 101L, 1, 7000L));
        when(orderClient.getByOrderNo(any())).thenReturn(Result.success(order));
        when(windowMapper.selectByOrderNo(any())).thenReturn(null);
        stubApplyItemRefs();

        String no = service.apply(applyReq(AftersaleTypes.RETURN_REFUND, 11L, 1), USER);

        assertEquals("AS202609160000000009", no);
        ArgumentCaptor<AftersaleOrder> cap = ArgumentCaptor.forClass(AftersaleOrder.class);
        verify(orderMapper).insert(cap.capture());
        AftersaleOrder saved = cap.getValue();
        assertEquals(7000L, saved.getRefundFen());
        assertNotNull(saved.getConfirmTime());
        assertNotNull(saved.getFreeAftersaleDeadline());
        assertEquals(saved.getConfirmTime().plusDays(15), saved.getFreeAftersaleDeadline());
        assertNotNull(saved.getWarrantyDeadline());
    }

    @Test
    void apply_已完成订单_仅有SHIPPED旧投影_补收货时间与窗口期放行() {
        // 回归 E2E：窗口行停在 ORDER_SHIPPED 投影（status=30、无收货/截止时间），实时订单已完成
        AftersaleWindow stale = new AftersaleWindow();
        stale.setId(7L);
        stale.setOrderStatus(30);
        stale.setOrderType(1);
        stale.setShippedTime(LocalDateTime.now().minusMinutes(30));
        stale.setWarrantyDays(15);
        OrderDTO order = completedOrder(4500L, orderItem(11L, 101L, 1, 4500L));
        when(orderClient.getByOrderNo(any())).thenReturn(Result.success(order));
        when(windowMapper.selectByOrderNo(any())).thenReturn(stale);
        stubApplyItemRefs();

        service.apply(applyReq(AftersaleTypes.RESHIP, 11L, 1), USER);

        // 投影行已存在：不新增窗口行；售后单仍带出实时收货起点与 15 天窗口期
        verify(windowMapper, never()).insert(any(AftersaleWindow.class));
        ArgumentCaptor<AftersaleOrder> cap = ArgumentCaptor.forClass(AftersaleOrder.class);
        verify(orderMapper).insert(cap.capture());
        assertNotNull(cap.getValue().getFreeAftersaleDeadline());
        assertEquals(0L, cap.getValue().getRefundFen());
    }

    @Test
    void apply_已完成订单收货超15天_窗口补齐后仍拒绝() {
        // 窗口期补齐不放松校验：真实收货时间在 16 天前 → 拒绝
        OrderDTO order = completedOrder(7000L, orderItem(11L, 101L, 1, 7000L));
        order.setConfirmTime(LocalDateTime.now().minusDays(16));
        when(orderClient.getByOrderNo(any())).thenReturn(Result.success(order));
        when(windowMapper.selectByOrderNo(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.apply(applyReq(AftersaleTypes.RETURN_REFUND, 11L, 1), USER));
        assertEquals(80001, ex.getCode());
        verify(orderMapper, never()).insert(any(AftersaleOrder.class));
    }

    @Test
    void trialPriceProtect_投影缺失_仍返回订单原价结构() {
        // 回归 E2E：窗口投影未落地不影响价保试算，原价取订单实付单价，价保期以下单时间为起点
        OrderDTO order = completedOrder(9000L, orderItem(11L, 101L, 1, 9000L));
        when(orderClient.getByOrderNo(any())).thenReturn(Result.success(order));
        when(windowMapper.selectByOrderNo(any())).thenReturn(null);
        when(priceProtectMapper.selectActiveByOrderNo(any())).thenReturn(null);
        when(orderMapper.countPriceProtect(any())).thenReturn(0);
        when(productClient.getSku(101L)).thenReturn(Result.success(SkuDTO.builder()
                .skuId(101L).salePriceFen(9000L).build()));

        PriceProtectTrialRequest req = new PriceProtectTrialRequest();
        req.setOrderNo("O202609160001");
        req.setOrderItemId(11L);
        req.setBigPromotion(false);
        PriceProtectTrialVO vo = service.trialPriceProtect(req, USER);

        assertEquals(9000L, vo.getOriginalUnitFen());
        assertEquals(9000L, vo.getCurrentPriceFen());
        assertEquals(0L, vo.getDiffUnitFen());
        assertEquals(0L, vo.getDiffTotalFen());
        assertFalse(vo.isEligible());
    }

    @Test
    void apply_换货_不退款且走2天审核() {
        AftersaleWindow w = new AftersaleWindow();
        w.setOrderStatus(40);
        w.setOrderType(1);
        w.setFreeAftersaleDeadline(LocalDateTime.now().plusDays(10));
        w.setWarrantyDeadline(LocalDateTime.now().plusDays(10));
        w.setCreateTime(LocalDateTime.now().minusDays(1));
        OrderDTO order = order(40, 1, 11000L, 1000L, orderItem(11L, 101L, 1, 10000L));
        when(orderClient.getByOrderNo(any())).thenReturn(Result.success(order));
        when(windowMapper.selectByOrderNo(any())).thenReturn(w);
        when(itemRefMapper.selectByOrderItemId(11L)).thenReturn(null);
        when(itemRefMapper.occupy(any(), any())).thenReturn(1);
        when(itemRefMapper.selectList(any())).thenReturn(List.of());
        when(noGenerator.nextAftersaleNo()).thenReturn("AS202609160000000002");

        String no = service.apply(applyReq(AftersaleTypes.EXCHANGE, 11L, 1), USER);
        ArgumentCaptor<AftersaleOrder> cap = ArgumentCaptor.forClass(AftersaleOrder.class);
        verify(orderMapper).insert(cap.capture());
        assertEquals(0L, cap.getValue().getRefundFen());
        assertEquals("AS202609160000000002", no);
        verify(outboxPublisher).publishDelay(any(), any(), any(), any(), eq(2 * 24 * 3600L));
    }

    @Test
    void apply_价保_排除秒杀拼团活动价_只按普通售价补差() {
        // 秒杀订单直接拒绝价保
        OrderDTO seckill = order(40, 2, 10000L, 0L, orderItem(11L, 101L, 1, 10000L));
        when(orderClient.getByOrderNo(any())).thenReturn(Result.success(seckill));
        AftersaleWindow w = new AftersaleWindow();
        w.setOrderStatus(40);
        w.setOrderType(2);
        w.setCreateTime(LocalDateTime.now().minusDays(1));
        when(windowMapper.selectByOrderNo(any())).thenReturn(w);
        assertThrows(BizException.class,
                () -> service.apply(applyReq(AftersaleTypes.PRICE_PROTECT, 11L, 1), USER));

        // 普通订单：购买 10000，当前普通价 8000（秒杀价再低也不取）→ 补差 2000
        OrderDTO normal = order(40, 1, 10000L, 0L, orderItem(11L, 101L, 1, 10000L));
        when(orderClient.getByOrderNo(any())).thenReturn(Result.success(normal));
        AftersaleWindow w2 = new AftersaleWindow();
        w2.setOrderStatus(40);
        w2.setOrderType(1);
        w2.setCreateTime(LocalDateTime.now().minusDays(1));
        when(windowMapper.selectByOrderNo(any())).thenReturn(w2);
        when(productClient.getSku(101L)).thenReturn(Result.success(SkuDTO.builder()
                .skuId(101L).salePriceFen(8000L).seckillPriceFen(5000L).build()));
        when(priceProtectMapper.selectActiveByOrderNo(any())).thenReturn(null);
        when(orderMapper.countPriceProtect(any())).thenReturn(0);
        when(itemRefMapper.selectByOrderItemId(11L)).thenReturn(null);
        when(itemRefMapper.occupy(any(), any())).thenReturn(1);
        when(itemRefMapper.selectList(any())).thenReturn(List.of());
        when(noGenerator.nextAftersaleNo()).thenReturn("AS202609160000000005");

        service.apply(applyReq(AftersaleTypes.PRICE_PROTECT, 11L, 1), USER);
        ArgumentCaptor<AftersaleOrder> cap = ArgumentCaptor.forClass(AftersaleOrder.class);
        verify(orderMapper).insert(cap.capture());
        assertEquals(2000L, cap.getValue().getRefundFen());
        verify(priceProtectMapper).insert(any());
    }

    @Test
    void apply_非本人订单_禁止() {
        OrderDTO order = order(20, 1, 11000L, 1000L, orderItem(11L, 101L, 2, 10000L));
        when(orderClient.getByOrderNo(any())).thenReturn(Result.success(order));
        assertThrows(BizException.class,
                () -> service.apply(applyReq(AftersaleTypes.REFUND_ONLY, 11L, 2), 9999L));
    }

    // ---------------- 商家审核 / 退款链路 ----------------

    @Test
    void audit_仅退款同意_发起退款并进入退款中() {
        AftersaleOrder o = aftersale(AftersaleTypes.REFUND_ONLY, AftersaleStatuses.WAIT_MERCHANT_AUDIT, 5000L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        stubRefundFlow();

        service.audit(o.getAftersaleNo(), MERCHANT, true, null);

        assertEquals(AftersaleStatuses.REFUNDING, o.getStatus());
        assertEquals("R20260916000000001", o.getRefundNo());
        ArgumentCaptor<CreateRefundCommand> cap = ArgumentCaptor.forClass(CreateRefundCommand.class);
        verify(payClient).refund(cap.capture());
        assertEquals(5000L, cap.getValue().getAmountFen());
        // P1-11：调支付域前退款单已先以独立事务落库，Feign 命令携带同一 refundNo
        ArgumentCaptor<AftersaleRefund> refundCap = ArgumentCaptor.forClass(AftersaleRefund.class);
        verify(refundStore).insertWaitingInNewTx(refundCap.capture());
        assertEquals("R20260916000000001", refundCap.getValue().getRefundNo());
        assertEquals(AftersaleCodes.REFUND_WAIT, refundCap.getValue().getStatus());
        assertEquals("R20260916000000001", cap.getValue().getRefundNo());
    }

    @Test
    void audit_调支付域失败_refundNo先落库且FAIL用独立事务保留() {
        // P1-11/P2-8：refundNo 先生成并 REQUIRES_NEW 落库 → Feign 失败 → FAIL 同样 REQUIRES_NEW 落库
        AftersaleOrder o = aftersale(AftersaleTypes.REFUND_ONLY, AftersaleStatuses.WAIT_MERCHANT_AUDIT, 5000L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(refundMapper.selectByAftersaleNo(any())).thenReturn(null);
        when(noGenerator.nextRefundNo()).thenReturn("RFIXED0000000000001");
        when(payClient.refund(any())).thenReturn(Result.fail(60001, "渠道退款失败"));

        BizException ex = assertThrows(BizException.class,
                () -> service.audit(o.getAftersaleNo(), MERCHANT, true, null));
        assertEquals("渠道退款失败", ex.getMessage());

        // 顺序：先落库退款单（独立事务）→ 再调支付域 → 失败后独立事务置 FAIL
        var order = inOrder(refundStore, payClient);
        ArgumentCaptor<AftersaleRefund> refundCap = ArgumentCaptor.forClass(AftersaleRefund.class);
        order.verify(refundStore).insertWaitingInNewTx(refundCap.capture());
        assertEquals("RFIXED0000000000001", refundCap.getValue().getRefundNo());
        ArgumentCaptor<CreateRefundCommand> cmdCap = ArgumentCaptor.forClass(CreateRefundCommand.class);
        order.verify(payClient).refund(cmdCap.capture());
        assertEquals("RFIXED0000000000001", cmdCap.getValue().getRefundNo());
        order.verify(refundStore).markFailInNewTx(eq("RFIXED0000000000001"), anyString());
        // 外层事务回滚：售后单未推进到退款中
        verify(orderMapper, never()).updateStatus(any(), anyInt(), anyInt());
    }

    @Test
    void audit_FAIL退款单重试_复用同一refundNo不新建() {
        // P1-11 重试场景：上次调支付域失败（FAIL 已独立事务落库），再次审核必须复用同一 refundNo
        AftersaleOrder o = aftersale(AftersaleTypes.REFUND_ONLY, AftersaleStatuses.WAIT_MERCHANT_AUDIT, 5000L);
        AftersaleRefund failed = new AftersaleRefund();
        failed.setRefundNo("ROLD00000000000001");
        failed.setAftersaleNo(o.getAftersaleNo());
        failed.setOrderNo(o.getOrderNo());
        failed.setUserId(USER);
        failed.setAmountFen(5000L);
        failed.setRefundType(2);
        failed.setStatus(AftersaleCodes.REFUND_FAIL);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(refundMapper.selectByAftersaleNo(any())).thenReturn(failed);
        when(payClient.refund(any())).thenReturn(Result.success(RefundDTO.builder()
                .refundNo("ROLD00000000000001").payMethod(1).status(30).build()));
        // WAIT→PROCESSING CAS 0 行（当前是 FAIL），FAIL→PROCESSING CAS 1 行
        when(refundMapper.updateStatus(any(), eq(AftersaleCodes.REFUND_WAIT),
                eq(AftersaleCodes.REFUND_PROCESSING), any(), any())).thenReturn(0);
        when(refundMapper.updateStatus(any(), eq(AftersaleCodes.REFUND_FAIL),
                eq(AftersaleCodes.REFUND_PROCESSING), any(), any())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of());

        service.audit(o.getAftersaleNo(), MERCHANT, true, null);

        ArgumentCaptor<CreateRefundCommand> cmdCap = ArgumentCaptor.forClass(CreateRefundCommand.class);
        verify(payClient).refund(cmdCap.capture());
        assertEquals("ROLD00000000000001", cmdCap.getValue().getRefundNo());
        // 不允许再生成/落库新的退款单号
        verify(noGenerator, never()).nextRefundNo();
        verify(refundStore, never()).insertWaitingInNewTx(any());
        assertEquals(AftersaleStatuses.REFUNDING, o.getStatus());
        assertEquals("ROLD00000000000001", o.getRefundNo());
    }

    @Test
    void audit_PROCESSING退款单_支付域已受理_不重复调用仅幂等推进() {
        // 崩溃窗口恢复：退款单已 PROCESSING（支付域已受理），重新流转时不得再次发起退款，
        // 只幂等推进售后单到退款中，等待 REFUND_SUCCESS 事件
        AftersaleOrder o = aftersale(AftersaleTypes.REFUND_ONLY, AftersaleStatuses.WAIT_MERCHANT_AUDIT, 5000L);
        AftersaleRefund processing = new AftersaleRefund();
        processing.setRefundNo("RRUN0000000000001");
        processing.setAftersaleNo(o.getAftersaleNo());
        processing.setOrderNo(o.getOrderNo());
        processing.setUserId(USER);
        processing.setAmountFen(5000L);
        processing.setRefundType(2);
        processing.setStatus(AftersaleCodes.REFUND_PROCESSING);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(refundMapper.selectByAftersaleNo(any())).thenReturn(processing);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of());

        service.audit(o.getAftersaleNo(), MERCHANT, true, null);

        verify(payClient, never()).refund(any());
        verify(refundStore, never()).insertWaitingInNewTx(any());
        assertEquals(AftersaleStatuses.REFUNDING, o.getStatus());
        assertEquals("RRUN0000000000001", o.getRefundNo());
    }

    @Test
    void audit_补发同意_进入待发货且不退款() {
        AftersaleOrder o = aftersale(AftersaleTypes.RESHIP, AftersaleStatuses.WAIT_MERCHANT_AUDIT, 0L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of());

        service.audit(o.getAftersaleNo(), MERCHANT, true, null);

        assertEquals(AftersaleStatuses.WAIT_EXCHANGE_SHIP, o.getStatus());
        verify(payClient, never()).refund(any());
    }

    @Test
    void audit_拒绝_缺原因抛异常() {
        AftersaleOrder o = aftersale(AftersaleTypes.REFUND_ONLY, 10, 5000L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        assertThrows(BizException.class,
                () -> service.audit(o.getAftersaleNo(), MERCHANT, false, ""));
    }

    @Test
    void audit_非所属商户_禁止() {
        AftersaleOrder o = aftersale(AftersaleTypes.REFUND_ONLY, 10, 5000L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        assertThrows(BizException.class,
                () -> service.audit(o.getAftersaleNo(), 8888L, true, null));
    }

    // ---------------- 超时自动流转 ----------------

    @Test
    void autoApprove_仅退款2天超时_自动同意退款() {
        AftersaleOrder o = aftersale(AftersaleTypes.REFUND_ONLY, 10, 5000L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        stubRefundFlow();

        service.autoApprove(o.getAftersaleNo());

        assertEquals(AftersaleStatuses.REFUNDING, o.getStatus());
        verify(payClient).refund(any());
    }

    @Test
    void autoApprove_状态已流转_幂等无操作() {
        AftersaleOrder o = aftersale(AftersaleTypes.REFUND_ONLY, 20, 5000L);
        when(orderMapper.selectByNo(any())).thenReturn(o);

        service.autoApprove(o.getAftersaleNo());

        verify(payClient, never()).refund(any());
    }

    @Test
    void autoConfirmReceive_退货退款3天超时_买家责任回库并退款() {
        AftersaleOrder o = aftersale(AftersaleTypes.RETURN_REFUND, 30, 10000L);
        AftersaleItem it = new AftersaleItem();
        it.setAftersaleNo(o.getAftersaleNo());
        it.setOrderItemId(11L);
        it.setSkuId(101L);
        it.setQty(1);
        it.setRefundFen(10000L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of(it));
        when(productClient.returnStock(any())).thenReturn(Result.success());
        when(refundMapper.selectByAftersaleNo(any())).thenReturn(null);
        when(noGenerator.nextRefundNo()).thenReturn("R20260916000000002");
        when(payClient.refund(any())).thenReturn(Result.success(RefundDTO.builder().payMethod(1).build()));
        when(refundMapper.updateStatus(any(), anyInt(), anyInt(), any(), any())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);

        service.autoConfirmReceive(o.getAftersaleNo());

        verify(productClient).returnStock(any());
        verify(payClient).refund(any());
        assertEquals(AftersaleStatuses.REFUNDING, o.getStatus());
    }

    @Test
    void autoConvertExchangeToRefund_换货5天未发货_转退款() {
        AftersaleOrder o = aftersale(AftersaleTypes.EXCHANGE, 41, 0L);
        AftersaleItem it = new AftersaleItem();
        it.setOrderItemId(11L);
        it.setSkuId(101L);
        it.setQty(1);
        it.setPaidFen(10000L);
        it.setRefundFen(0L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of(it));
        when(refundMapper.selectByAftersaleNo(any())).thenReturn(null);
        when(noGenerator.nextRefundNo()).thenReturn("R20260916000000003");
        when(payClient.refund(any())).thenReturn(Result.success(RefundDTO.builder().payMethod(1).build()));
        when(refundMapper.updateStatus(any(), anyInt(), anyInt(), any(), any())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);

        service.autoConvertExchangeToRefund(o.getAftersaleNo());

        ArgumentCaptor<CreateRefundCommand> cap = ArgumentCaptor.forClass(CreateRefundCommand.class);
        verify(payClient).refund(cap.capture());
        assertEquals(10000L, cap.getValue().getAmountFen());
        assertEquals(AftersaleStatuses.REFUNDING, o.getStatus());
    }

    // ---------------- 平台介入 / 仲裁 ----------------

    @Test
    void applyIntervene_已拒绝_进入介入() {
        AftersaleOrder o = aftersale(AftersaleTypes.RETURN_REFUND, 55, 10000L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(disputeMapper.selectByAftersaleNo(any())).thenReturn(null);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of());

        service.applyIntervene(o.getAftersaleNo(), USER);

        assertEquals(AftersaleStatuses.PLATFORM_INTERVENING, o.getStatus());
        verify(disputeMapper).insert(any(AftersaleDispute.class));
        verify(outboxPublisher).publishDelay(any(), any(), any(), any(), anyLong());
    }

    @Test
    void applyIntervene_审核中且未超时_拒绝介入() {
        AftersaleOrder o = aftersale(AftersaleTypes.RETURN_REFUND, 10, 10000L);
        o.setAuditDeadline(LocalDateTime.now().plusDays(1));
        when(orderMapper.selectByNo(any())).thenReturn(o);
        assertThrows(BizException.class, () -> service.applyIntervene(o.getAftersaleNo(), USER));
    }

    @Test
    void arbitrate_部分支持_按裁定金额退款() {
        AftersaleOrder o = aftersale(AftersaleTypes.RETURN_REFUND, 80, 10000L);
        AftersaleDispute d = new AftersaleDispute();
        d.setId(1L);
        d.setAftersaleNo(o.getAftersaleNo());
        d.setStatus(10);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(disputeMapper.selectByAftersaleNo(any())).thenReturn(d);
        stubRefundFlow();

        ArbitrateRequest req = new ArbitrateRequest();
        req.setResult(ArbitrationResults.PARTIAL);
        req.setAwardFen(3000L);
        service.arbitrate(o.getAftersaleNo(), req);

        ArgumentCaptor<CreateRefundCommand> cap = ArgumentCaptor.forClass(CreateRefundCommand.class);
        verify(payClient).refund(cap.capture());
        assertEquals(3000L, cap.getValue().getAmountFen());
        assertEquals(AftersaleStatuses.REFUNDING, o.getStatus());
        // O5：退款状态日志必须记录真实平台运营 ID，禁止 operatorId=0
        StatusLog log = captureSingleStatusLog();
        assertEquals(PLATFORM_ADMIN, log.getOperatorId());
        assertNotEquals(0L, log.getOperatorId());
        assertEquals(AftersaleCodes.ROLE_PLATFORM, log.getOperatorRole());
    }

    @Test
    void arbitrate_商家胜诉_维持拒绝并释放明细() {
        AftersaleOrder o = aftersale(AftersaleTypes.RETURN_REFUND, 80, 10000L);
        AftersaleDispute d = new AftersaleDispute();
        d.setId(1L);
        d.setStatus(10);
        AftersaleItem it = new AftersaleItem();
        it.setOrderItemId(11L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(disputeMapper.selectByAftersaleNo(any())).thenReturn(d);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of(it));
        when(itemRefMapper.release(11L, o.getAftersaleNo())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);

        ArbitrateRequest req = new ArbitrateRequest();
        req.setResult(ArbitrationResults.MERCHANT_WIN);
        service.arbitrate(o.getAftersaleNo(), req);

        assertEquals(AftersaleStatuses.REJECTED, o.getStatus());
        verify(itemRefMapper).release(11L, o.getAftersaleNo());
        verify(payClient, never()).refund(any());
        // O5：商家胜诉分支同样以真实运营 ID 留痕
        StatusLog log = captureSingleStatusLog();
        assertEquals(PLATFORM_ADMIN, log.getOperatorId());
        assertNotEquals(0L, log.getOperatorId());
        assertEquals(AftersaleCodes.ROLE_PLATFORM, log.getOperatorRole());
    }

    @Test
    void arbitrate_买家胜诉_执行退款且操作人为真实平台运营() {
        AftersaleOrder o = aftersale(AftersaleTypes.RETURN_REFUND, 80, 8000L);
        AftersaleDispute d = new AftersaleDispute();
        d.setId(2L);
        d.setAftersaleNo(o.getAftersaleNo());
        d.setStatus(10);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(disputeMapper.selectByAftersaleNo(any())).thenReturn(d);
        stubRefundFlow();

        ArbitrateRequest req = new ArbitrateRequest();
        req.setResult(ArbitrationResults.BUYER_WIN);
        service.arbitrate(o.getAftersaleNo(), req);

        verify(payClient).refund(any(CreateRefundCommand.class));
        assertEquals(AftersaleStatuses.REFUNDING, o.getStatus());
        StatusLog log = captureSingleStatusLog();
        assertEquals(PLATFORM_ADMIN, log.getOperatorId());
        assertNotEquals(0L, log.getOperatorId());
        assertEquals(AftersaleCodes.ROLE_PLATFORM, log.getOperatorRole());
    }

    @Test
    void arbitrate_买家胜诉_换货单进入待发货且操作人为真实平台运营() {
        AftersaleOrder o = aftersale(AftersaleTypes.EXCHANGE, 80, 0L);
        AftersaleDispute d = new AftersaleDispute();
        d.setId(3L);
        d.setAftersaleNo(o.getAftersaleNo());
        d.setStatus(10);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(disputeMapper.selectByAftersaleNo(any())).thenReturn(d);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of());

        ArbitrateRequest req = new ArbitrateRequest();
        req.setResult(ArbitrationResults.BUYER_WIN);
        service.arbitrate(o.getAftersaleNo(), req);

        assertEquals(AftersaleStatuses.WAIT_EXCHANGE_SHIP, o.getStatus());
        verify(payClient, never()).refund(any());
        StatusLog log = captureSingleStatusLog();
        assertEquals(PLATFORM_ADMIN, log.getOperatorId());
        assertNotEquals(0L, log.getOperatorId());
        assertEquals(AftersaleCodes.ROLE_PLATFORM, log.getOperatorRole());
    }

    // ---------------- 换货发货 / 签收 ----------------

    @Test
    void shipExchange_库存充足_发货进入待收货() {
        AftersaleOrder o = aftersale(AftersaleTypes.EXCHANGE, 41, 0L);
        o.setExchangeSkuId(102L);
        AftersaleItem it = new AftersaleItem();
        it.setSkuId(102L);
        it.setQty(1);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of(it));
        when(productClient.saleable(102L, 1)).thenReturn(Result.success(true));
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);

        LogisticsRequest req = new LogisticsRequest();
        req.setCompany("顺丰");
        req.setLogisticsNo("SF123");
        service.shipExchange(o.getAftersaleNo(), MERCHANT, req);

        assertEquals(AftersaleStatuses.EXCHANGE_WAIT_RECEIVE, o.getStatus());
    }

    @Test
    void shipExchange_换货库存不足_抛异常() {
        AftersaleOrder o = aftersale(AftersaleTypes.EXCHANGE, 41, 0L);
        o.setExchangeSkuId(102L);
        AftersaleItem it = new AftersaleItem();
        it.setSkuId(102L);
        it.setQty(1);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of(it));
        when(productClient.saleable(102L, 1)).thenReturn(Result.success(false));

        LogisticsRequest req = new LogisticsRequest();
        req.setCompany("顺丰");
        req.setLogisticsNo("SF123");
        assertThrows(BizException.class,
                () -> service.shipExchange(o.getAftersaleNo(), MERCHANT, req));
    }

    // ---------------- H-1 平台仲裁垂直越权 ----------------

    @Test
    void arbitrate_普通用户调用_403() {
        UserContext.set(LoginUser.builder().userId(USER).userType(0).build());
        ArbitrateRequest req = new ArbitrateRequest();
        req.setResult(ArbitrationResults.MERCHANT_WIN);
        BizException ex = assertThrows(BizException.class,
                () -> service.arbitrate("AS202609160000000001", req));
        assertEquals(10003, ex.getCode());
        verify(disputeMapper, never()).selectByAftersaleNo(any());
    }

    @Test
    void arbitrate_商户身份调用_403() {
        UserContext.set(LoginUser.builder().userId(50L).userType(1).merchantId(MERCHANT).build());
        ArbitrateRequest req = new ArbitrateRequest();
        req.setResult(ArbitrationResults.MERCHANT_WIN);
        BizException ex = assertThrows(BizException.class,
                () -> service.arbitrate("AS202609160000000001", req));
        assertEquals(10003, ex.getCode());
    }

    @Test
    void arbitrate_未登录_401且不进入仲裁逻辑() {
        UserContext.clear();
        ArbitrateRequest req = new ArbitrateRequest();
        req.setResult(ArbitrationResults.MERCHANT_WIN);
        BizException ex = assertThrows(BizException.class,
                () -> service.arbitrate("AS202609160000000001", req));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        verify(disputeMapper, never()).selectByAftersaleNo(any());
        verify(statusLogMapper, never()).insert(any());
    }

    // ---------------- H-2 售后详情 IDOR ----------------

    @Test
    void detail_买家本人_可查() {
        when(orderMapper.selectByNo(any())).thenReturn(aftersale(AftersaleTypes.REFUND_ONLY, 10, 100L));
        LoginUser buyer = LoginUser.builder().userId(USER).userType(0).build();
        assertEquals(USER, service.detail("AS202609160000000001", buyer).getAftersale().getUserId());
    }

    @Test
    void detail_订单归属商户_可查() {
        when(orderMapper.selectByNo(any())).thenReturn(aftersale(AftersaleTypes.REFUND_ONLY, 10, 100L));
        LoginUser merchant = LoginUser.builder().userId(50L).userType(1).merchantId(MERCHANT).build();
        assertEquals(MERCHANT, service.detail("AS202609160000000001", merchant).getAftersale().getMerchantId());
    }

    @Test
    void detail_平台运营_可查() {
        when(orderMapper.selectByNo(any())).thenReturn(aftersale(AftersaleTypes.REFUND_ONLY, 10, 100L));
        LoginUser admin = LoginUser.builder().userId(1L).userType(2).build();
        assertEquals("AS202609160000000001",
                service.detail("AS202609160000000001", admin).getAftersale().getAftersaleNo());
    }

    @Test
    void detail_遍历他人单号_403() {
        when(orderMapper.selectByNo(any())).thenReturn(aftersale(AftersaleTypes.REFUND_ONLY, 10, 100L));
        LoginUser other = LoginUser.builder().userId(9999L).userType(0).build();
        BizException ex = assertThrows(BizException.class,
                () -> service.detail("AS202609160000000001", other));
        assertEquals(10003, ex.getCode());
        verify(itemMapper, never()).selectByAftersaleNo(any());
    }

    @Test
    void detail_非归属商户_403() {
        when(orderMapper.selectByNo(any())).thenReturn(aftersale(AftersaleTypes.REFUND_ONLY, 10, 100L));
        LoginUser otherMerchant = LoginUser.builder().userId(51L).userType(1).merchantId(9999L).build();
        BizException ex = assertThrows(BizException.class,
                () -> service.detail("AS202609160000000001", otherMerchant));
        assertEquals(10003, ex.getCode());
    }

    // ======================== R-B6：returnStock 三类失败形态（9 用例之 3） ========================

    private void stubBuyerReceiveReady() {
        AftersaleOrder o = aftersale(AftersaleTypes.RETURN_REFUND,
                AftersaleStatuses.MERCHANT_RECEIVING, 10000L);
        AftersaleItem it = new AftersaleItem();
        it.setAftersaleNo(o.getAftersaleNo());
        it.setOrderItemId(11L);
        it.setSkuId(101L);
        it.setQty(1);
        it.setRefundFen(10000L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of(it));
    }

    @Test
    void autoConfirmReceive_退货入库返回null_整体失败不发起退款() {
        stubBuyerReceiveReady();
        when(productClient.returnStock(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.autoConfirmReceive("AS202609160000000001"));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
        verify(productClient).returnStock(any());
        verify(payClient, never()).refund(any());
        // 售后单不离开商家收货中（无 30→40 推进）
        verify(orderMapper, never()).updateStatus(any(), anyInt(), anyInt());
    }

    @Test
    void autoConfirmReceive_退货入库非success_整体失败() {
        stubBuyerReceiveReady();
        when(productClient.returnStock(any())).thenReturn(Result.fail(50000, "库存服务内部错误"));

        BizException ex = assertThrows(BizException.class,
                () -> service.autoConfirmReceive("AS202609160000000001"));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
        verify(payClient, never()).refund(any());
    }

    @Test
    void autoConfirmReceive_库存服务HTTP500_异常外抛可由扫表重试() {
        stubBuyerReceiveReady();
        when(productClient.returnStock(any()))
                .thenThrow(FeignFailures.serverError500("ProductClient#returnStock"));

        BizException ex = assertThrows(BizException.class,
                () -> service.autoConfirmReceive("AS202609160000000001"));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
        verify(payClient, never()).refund(any());
    }

    // ======================== R-B6 × P2-5：渠道退款 PROCESSING 受理语义 ========================

    @Test
    void audit_支付域同步返回PROCESSING_售后停留退款中不报错等待REFUND_SUCCESS() {
        AftersaleOrder o = aftersale(AftersaleTypes.REFUND_ONLY,
                AftersaleStatuses.WAIT_MERCHANT_AUDIT, 5000L);
        when(orderMapper.selectByNo(any())).thenReturn(o);
        when(refundMapper.selectByAftersaleNo(any())).thenReturn(null);
        when(noGenerator.nextRefundNo()).thenReturn("RPROC000000000001");
        // 20=渠道受理中：非 FAIL、非异常，售后侧不得判失败
        when(payClient.refund(any())).thenReturn(Result.success(RefundDTO.builder()
                .refundNo("RPROC000000000001").payMethod(1).status(20).build()));
        when(refundMapper.updateStatus(any(), anyInt(), anyInt(), any(), any())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(itemMapper.selectByAftersaleNo(any())).thenReturn(List.of());

        service.audit(o.getAftersaleNo(), MERCHANT, true, null);

        // 停留退款中，终态只等 REFUND_SUCCESS 事件；FAIL 留痕不触发
        assertEquals(AftersaleStatuses.REFUNDING, o.getStatus());
        verify(refundStore, never()).markFailInNewTx(any(), any());
        verify(payClient).refund(any());
    }

    // ======================== R-B6：私有 unwrap 已切框架 FeignResults ========================

    @Test
    void feignResults_nullResult_抛DEPENDENCY_FAIL() {
        BizException ex = assertThrows(BizException.class, () -> FeignResults.unwrap(null));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
    }

    @Test
    void feignResults_非success_业务码透传不透支成功() {
        BizException ex = assertThrows(BizException.class,
                () -> FeignResults.unwrap(Result.fail(60001, "渠道退款失败")));
        assertEquals(60001, ex.getCode());
    }
}
