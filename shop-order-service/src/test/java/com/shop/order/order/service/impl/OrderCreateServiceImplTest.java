package com.shop.order.order.service.impl;

import com.shop.api.marketing.client.MarketingClient;
import com.shop.api.marketing.dto.PriceCalcCommand;
import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.api.order.event.OrderCreatedEvent;
import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.FreightCalcResponse;
import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.enums.GoodsStatuses;
import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.AddressDTO;
import com.shop.api.user.dto.UserDTO;
import com.shop.api.user.dto.UserLevelDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.framework.idempotent.Idempotent;
import com.shop.order.idgen.OrderNoGenerator;
import com.shop.order.mq.message.OrderDelayMessage;
import com.shop.order.order.dto.CreateOrderRequest;
import com.shop.order.order.entity.Order;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.service.OrderAggregate;
import com.shop.order.order.service.OrderPersister;
import com.shop.order.policy.PayTimeoutPolicy;
import com.shop.order.support.FreightInsuranceCalculator;
import com.shop.order.support.OrderAssembler;
import com.shop.order.support.PurchaseLimitChecker;
import com.shop.order.support.RegionDeliveryChecker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单编排核心单测（纯 Mockito，无 Spring/中间件）：
 * 8 项前置校验失败路径、try 各步骤失败的逆序补偿调用次数、成功路径锁顺序与事件。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCreateServiceImplTest {

    @Mock
    private ProductClient productClient;
    @Mock
    private MarketingClient marketingClient;
    @Mock
    private UserClient userClient;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderNoGenerator orderNoGenerator;
    @Mock
    private OrderPersister orderPersister;
    @Mock
    private PurchaseLimitChecker purchaseLimitChecker;
    @Mock
    private OrderAssembler assembler;

    private OrderCreateServiceImpl service;

    /** O6：下单指标断言用注册表（SimpleMeterRegistry 内存实现）。 */
    private SimpleMeterRegistry meterRegistry;

    private static final Long USER_ID = 1001L;
    private static final String ORDER_NO = "260315011001000001";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // 区域配送与超时矩阵为纯逻辑组件，使用真实实例；运费险用默认 100 分固定保费
        service = new OrderCreateServiceImpl(productClient, marketingClient, userClient,
                orderItemMapper, orderNoGenerator, orderPersister,
                purchaseLimitChecker, new RegionDeliveryChecker(), new PayTimeoutPolicy(),
                assembler, new FreightInsuranceCalculator(100L), meterRegistry);

        when(orderNoGenerator.next(anyInt(), anyLong())).thenReturn(ORDER_NO);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private SkuDTO sku(long skuId, long shopId, long price, int status, long stock) {
        return SkuDTO.builder()
                .skuId(skuId).spuId(2001L).merchantId(3001L).shopId(shopId)
                .skuName("SKU" + skuId).specText("颜色:红").category3Id(501L)
                .salePriceFen(price).seckillPriceFen(price - 100)
                .status(status).availableStock(stock)
                .build();
    }

    private CreateOrderRequest normalRequest() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setClientToken("token-abc");
        req.setOrderType(1);
        req.setAddressId(9001L);
        CreateOrderRequest.Item item = new CreateOrderRequest.Item();
        item.setSkuId(11L);
        item.setQty(2);
        req.setItems(List.of(item));
        return req;
    }

    private AddressDTO validAddress() {
        return AddressDTO.builder()
                .addressId(9001L).userId(USER_ID)
                .receiver("张三").phone("13800000000")
                .province("浙江省").city("杭州市").district("西湖区")
                .detailAddress("文三路 100 号")
                .build();
    }

    private void stubPrechecks(CreateOrderRequest req, SkuDTO... skus) {
        when(productClient.listSkus(any())).thenReturn(Result.success(List.of(skus)));
        when(productClient.saleable(anyLong(), anyInt())).thenReturn(Result.success(Boolean.TRUE));
        when(orderItemMapper.sumPurchasedQty(anyLong(), anyLong())).thenReturn(0);
        when(userClient.getAddress(9001L)).thenReturn(Result.success(validAddress()));
        when(userClient.getUser(USER_ID)).thenReturn(Result.success(
                UserDTO.builder().userId(USER_ID).nickname("张三").build()));
        when(userClient.getLevel(USER_ID)).thenReturn(Result.success(
                UserLevelDTO.builder().userId(USER_ID).level(1).build()));
        // B7：运费服务端试算（默认 0 运费；具体用例可覆盖）
        when(productClient.calcFreight(any())).thenReturn(Result.success(
                FreightCalcResponse.builder().freightFen(0L).snapshotJson("{}").build()));
    }

    private PriceCalcResult calcResult(long productFen, long payFen, long pointsFen) {
        return PriceCalcResult.builder()
                .originalProductFen(productFen)
                .productPromoFen(0L).shopPromoFen(0L)
                .categoryCouponFen(0L).shopCouponFen(0L).platformCouponFen(0L)
                .freightCouponFen(0L).freightFen(0L)
                .pointsDeductFen(pointsFen)
                .payFen(payFen)
                .usedUserCouponIds(List.of())
                .snapshotJson("{}")
                .build();
    }

    private void stubLockSuccess() {
        when(productClient.lockStock(any())).thenReturn(Result.success());
        when(marketingClient.lockPromotion(any())).thenReturn(Result.success());
        when(userClient.lockPoints(any())).thenReturn(Result.success());
    }

    // ------------------------------------------------------------------
    // 成功路径
    // ------------------------------------------------------------------

    @Test
    void create_success_locksInOrder_persists_sendsCreatedAndTimeoutEvents() {
        CreateOrderRequest req = normalRequest();
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 20000L, 0L)));
        stubLockSuccess();

        String orderNo = service.create(req, USER_ID);

        assertThat(orderNo).isEqualTo(ORDER_NO);
        // try 顺序：库存 → 营销 →（无积分不锁）→ 落单
        verify(productClient).lockStock(any());
        verify(marketingClient).lockPromotion(any());
        verify(userClient, never()).lockPoints(any());
        verify(orderPersister).persist(any(OrderAggregate.class),
                any(OrderCreatedEvent.class), any(OrderDelayMessage.class));
        // 成功路径无补偿
        verify(productClient, never()).releaseStock(any());
        verify(marketingClient, never()).releasePromotion(any());
        verify(userClient, never()).releasePoints(any());
    }

    @Test
    void create_success_withPoints_locksPointsToo() {
        CreateOrderRequest req = normalRequest();
        req.setUsePointsFen(5000L);
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 15000L, 5000L)));
        stubLockSuccess();

        service.create(req, USER_ID);

        verify(productClient).lockStock(any());
        verify(marketingClient).lockPromotion(any());
        verify(userClient).lockPoints(any());
        verify(orderPersister).persist(any(OrderAggregate.class),
                any(OrderCreatedEvent.class), any(OrderDelayMessage.class));
    }

    // ------------------------------------------------------------------
    // O6 下单指标
    // ------------------------------------------------------------------

    @Test
    void metrics_createSuccess_recordsSuccessCounterAndTimer() {
        CreateOrderRequest req = normalRequest();
        req.setSource(1);
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 20000L, 0L)));
        stubLockSuccess();

        service.create(req, USER_ID);

        Counter success = meterRegistry.find(OrderCreateServiceImpl.ORDER_CREATED_TOTAL)
                .tags("result", "success", "channel", "APP").counter();
        assertThat(success).isNotNull();
        assertThat(success.count()).isEqualTo(1.0);
        assertThat(meterRegistry.find(OrderCreateServiceImpl.ORDER_CREATED_TOTAL)
                .tags("result", "fail", "channel", "APP").counter()).isNull();
        Timer timer = meterRegistry.find(OrderCreateServiceImpl.ORDER_CREATE_SECONDS)
                .tags("channel", "APP").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThan(0d);
    }

    @Test
    void metrics_createBizFailure_recordsFailCounterAndTimerAndStillThrows() {
        CreateOrderRequest req = normalRequest();
        req.setSource(3);
        // 商品已下架：前置校验 2 抛 GOODS_NOT_SALE（BizException 路径）
        when(productClient.listSkus(any())).thenReturn(Result.success(
                List.of(sku(11L, 4001L, 10000L, 4, 100L))));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.GOODS_NOT_SALE.getCode());

        Counter fail = meterRegistry.find(OrderCreateServiceImpl.ORDER_CREATED_TOTAL)
                .tags("result", "fail", "channel", "MINI").counter();
        assertThat(fail).isNotNull();
        assertThat(fail.count()).isEqualTo(1.0);
        assertThat(meterRegistry.find(OrderCreateServiceImpl.ORDER_CREATED_TOTAL)
                .tags("result", "success", "channel", "MINI").counter()).isNull();
        Timer timer = meterRegistry.find(OrderCreateServiceImpl.ORDER_CREATE_SECONDS)
                .tags("channel", "MINI").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    void metrics_tagsContainOnlyResultAndChannel_noOrderNoOrUserId() {
        CreateOrderRequest req = normalRequest();
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 20000L, 0L)));
        stubLockSuccess();
        service.create(req, USER_ID);
        when(productClient.listSkus(any())).thenReturn(Result.success(List.of()));
        assertThatThrownBy(() -> service.create(normalRequest(), USER_ID))
                .isInstanceOf(BizException.class);

        Set<String> allowed = Set.of("result", "channel");
        for (Meter meter : meterRegistry.getMeters()) {
            if (!meter.getId().getName().startsWith("shop_order_create")) {
                continue;
            }
            meter.getId().getTags().forEach(tag -> {
                assertThat(tag.getKey()).isIn(allowed);
                assertThat(tag.getKey()).doesNotContain("orderNo", "userId", "skuId", "merchantId");
            });
        }
        // 计数器序列齐备：success/fail 各一条 channel=APP
        assertThat(meterRegistry.find(OrderCreateServiceImpl.ORDER_CREATED_TOTAL)
                .tags("result", "success", "channel", "APP").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find(OrderCreateServiceImpl.ORDER_CREATED_TOTAL)
                .tags("result", "fail", "channel", "APP").counter().count()).isEqualTo(1.0);
    }

    @Test
    void metrics_withoutRegistry_bizFailureStillThrowsWithoutNpe() {
        OrderCreateServiceImpl noMetrics = new OrderCreateServiceImpl(productClient, marketingClient,
                userClient, orderItemMapper, orderNoGenerator, orderPersister,
                purchaseLimitChecker, new RegionDeliveryChecker(), new PayTimeoutPolicy(),
                assembler, new FreightInsuranceCalculator(100L), null);
        when(productClient.listSkus(any())).thenReturn(Result.success(List.of()));

        assertThatThrownBy(() -> noMetrics.create(normalRequest(), USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.GOODS_NOT_SALE.getCode());
    }

    // ------------------------------------------------------------------
    // 前置校验 1：登录态
    // ------------------------------------------------------------------

    @Test
    void create_withoutLogin_throwsUnauthorized() {
        assertThatThrownBy(() -> service.create(normalRequest(), null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
        verify(productClient, never()).listSkus(any());
        verify(orderNoGenerator, never()).next(anyInt(), anyLong());
    }

    // ------------------------------------------------------------------
    // 前置校验 2：商品状态
    // ------------------------------------------------------------------

    @Test
    void create_goodsNotOnSale_throwsGoodsNotSale() {
        CreateOrderRequest req = normalRequest();
        when(productClient.listSkus(any())).thenReturn(Result.success(
                List.of(sku(11L, 4001L, 10000L, 4, 100L))));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.GOODS_NOT_SALE.getCode());
        verify(productClient, never()).lockStock(any());
    }

    @Test
    void create_skuMissing_throwsGoodsNotSale() {
        CreateOrderRequest req = normalRequest();
        when(productClient.listSkus(any())).thenReturn(Result.success(List.of()));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.GOODS_NOT_SALE.getCode());
    }

    // ------------------------------------------------------------------
    // 前置校验 3：库存
    // ------------------------------------------------------------------

    @Test
    void create_stockNotEnough_throwsStockNotEnough() {
        CreateOrderRequest req = normalRequest();
        when(productClient.listSkus(any())).thenReturn(Result.success(
                List.of(sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 1L))));
        when(productClient.saleable(anyLong(), anyInt())).thenReturn(Result.success(Boolean.FALSE));
        when(orderItemMapper.sumPurchasedQty(anyLong(), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.STOCK_NOT_ENOUGH.getCode());
        verify(productClient, never()).lockStock(any());
    }

    // ------------------------------------------------------------------
    // 前置校验 4：限购
    // ------------------------------------------------------------------

    @Test
    void create_overPurchaseLimit_throwsLimitPurchase() {
        CreateOrderRequest req = normalRequest();
        when(productClient.listSkus(any())).thenReturn(Result.success(
                List.of(sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L))));
        when(productClient.saleable(anyLong(), anyInt())).thenReturn(Result.success(Boolean.TRUE));
        when(orderItemMapper.sumPurchasedQty(USER_ID, 11L)).thenReturn(9);
        doThrow(new BizException(ErrorCode.LIMIT_PURCHASE, "每人限购 10 件"))
                .when(purchaseLimitChecker).check(eq(11L), eq(2), eq(9L));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.LIMIT_PURCHASE.getCode());
        verify(productClient, never()).lockStock(any());
    }

    // ------------------------------------------------------------------
    // 一单一店铺
    // ------------------------------------------------------------------

    @Test
    void create_crossShop_throwsParamInvalid() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setClientToken("token-multi");
        req.setOrderType(1);
        req.setAddressId(9001L);
        CreateOrderRequest.Item i1 = new CreateOrderRequest.Item();
        i1.setSkuId(11L);
        i1.setQty(1);
        CreateOrderRequest.Item i2 = new CreateOrderRequest.Item();
        i2.setSkuId(12L);
        i2.setQty(1);
        req.setItems(List.of(i1, i2));

        when(productClient.listSkus(any())).thenReturn(Result.success(List.of(
                sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L),
                sku(12L, 4002L, 20000L, GoodsStatuses.ON_SALE.getCode(), 100L))));
        when(productClient.saleable(anyLong(), anyInt())).thenReturn(Result.success(Boolean.TRUE));
        when(orderItemMapper.sumPurchasedQty(anyLong(), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        verify(productClient, never()).lockStock(any());
    }

    // ------------------------------------------------------------------
    // 前置校验 5：区域配送（地址归属/完整性）
    // ------------------------------------------------------------------

    @Test
    void create_addressNotBelongToUser_throwsForbidden() {
        CreateOrderRequest req = normalRequest();
        when(productClient.listSkus(any())).thenReturn(Result.success(
                List.of(sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L))));
        when(productClient.saleable(anyLong(), anyInt())).thenReturn(Result.success(Boolean.TRUE));
        when(orderItemMapper.sumPurchasedQty(anyLong(), anyLong())).thenReturn(0);
        AddressDTO others = validAddress();
        others.setUserId(2002L);
        when(userClient.getAddress(9001L)).thenReturn(Result.success(others));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
        verify(productClient, never()).lockStock(any());
    }

    // ------------------------------------------------------------------
    // 前置校验 6：营销活动进行中
    // ------------------------------------------------------------------

    @Test
    void create_activityUnavailable_throwsActivityNotAvailable() {
        CreateOrderRequest req = normalRequest();
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.fail(ErrorCode.ACTIVITY_NOT_AVAILABLE, "活动已结束"));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_AVAILABLE.getCode());
        verify(productClient, never()).lockStock(any());
    }

    // ------------------------------------------------------------------
    // 前置校验 7：优惠券
    // ------------------------------------------------------------------

    @Test
    void create_requestedCouponNotUsedByCalc_throwsCouponNotAvailable() {
        CreateOrderRequest req = normalRequest();
        req.setShopCouponId(88L);
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 20000L, 0L)));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.COUPON_NOT_AVAILABLE.getCode());
        verify(productClient, never()).lockStock(any());
    }

    @Test
    void create_calcRejectsCoupon_errorCodePassesThrough() {
        CreateOrderRequest req = normalRequest();
        req.setShopCouponId(88L);
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.fail(ErrorCode.COUPON_NOT_AVAILABLE, "券已失效"));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.COUPON_NOT_AVAILABLE.getCode());
    }

    // ------------------------------------------------------------------
    // 前置校验（积分）：抵现不超过商品金额 50%
    // ------------------------------------------------------------------

    @Test
    void create_pointsOverHalfOfProduct_throwsPointsNotEnough() {
        CreateOrderRequest req = normalRequest();
        // 商品 10000*2=20000 分，50% 封顶 10000；请求 10001
        req.setUsePointsFen(10001L);
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.POINTS_NOT_ENOUGH.getCode());
        verify(marketingClient, never()).calculate(any());
        verify(productClient, never()).lockStock(any());
    }

    // ------------------------------------------------------------------
    // try 步骤失败 → 逆序补偿（design 5.3.2）
    // ------------------------------------------------------------------

    @Test
    void create_lockStockFails_noCompensationAtAll() {
        CreateOrderRequest req = normalRequest();
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 20000L, 0L)));
        when(productClient.lockStock(any()))
                .thenReturn(Result.fail(ErrorCode.STOCK_NOT_ENOUGH, "库存不足"));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.STOCK_NOT_ENOUGH.getCode());

        // 第一步就失败：没有任何已成功的 try，补偿调用全部为 0
        verify(productClient, never()).releaseStock(any());
        verify(marketingClient, never()).releasePromotion(any());
        verify(userClient, never()).releasePoints(any());
        verify(orderPersister, never()).persist(any(), any(), any());
    }

    @Test
    void create_lockPromotionFails_releasesStockOnly() {
        CreateOrderRequest req = normalRequest();
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 20000L, 0L)));
        when(productClient.lockStock(any())).thenReturn(Result.success());
        when(marketingClient.lockPromotion(any()))
                .thenReturn(Result.fail(ErrorCode.ACTIVITY_NOT_AVAILABLE, "活动被抢光"));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_AVAILABLE.getCode());

        verify(productClient, times(1)).releaseStock(any());
        verify(marketingClient, never()).releasePromotion(any());
        verify(userClient, never()).releasePoints(any());
        verify(orderPersister, never()).persist(any(), any(), any());
    }

    @Test
    void create_lockPointsFails_releasesPromotionAndStock_notPoints() {
        CreateOrderRequest req = normalRequest();
        req.setUsePointsFen(5000L);
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 15000L, 5000L)));
        when(productClient.lockStock(any())).thenReturn(Result.success());
        when(marketingClient.lockPromotion(any())).thenReturn(Result.success());
        when(userClient.lockPoints(any()))
                .thenReturn(Result.fail(ErrorCode.POINTS_NOT_ENOUGH, "积分不足"));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.POINTS_NOT_ENOUGH.getCode());

        // 积分锁定本身失败 → 不调 releasePoints；营销/库存已锁 → 各释放 1 次
        verify(userClient, never()).releasePoints(any());
        verify(marketingClient, times(1)).releasePromotion(any());
        verify(productClient, times(1)).releaseStock(any());
        verify(orderPersister, never()).persist(any(), any(), any());
    }

    @Test
    void create_persistFails_releasesAllThreeInReverseOrder() {
        CreateOrderRequest req = normalRequest();
        req.setUsePointsFen(5000L);
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 15000L, 5000L)));
        stubLockSuccess();
        doThrow(new RuntimeException("DB down")).when(orderPersister)
                .persist(any(), any(), any());

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class);

        // 三个 try 全部成功后落库失败：积分 → 营销 → 库存各释放 1 次
        verify(userClient, times(1)).releasePoints(any());
        verify(marketingClient, times(1)).releasePromotion(any());
        verify(productClient, times(1)).releaseStock(any());
    }

    // ------------------------------------------------------------------
    // 幂等注解
    // ------------------------------------------------------------------

    @Test
    void create_isAnnotatedIdempotentWithClientToken() throws Exception {
        Method method = OrderCreateServiceImpl.class.getMethod(
                "create", CreateOrderRequest.class, Long.class);
        Idempotent idempotent = method.getAnnotation(Idempotent.class);
        assertThat(idempotent).isNotNull();
        assertThat(idempotent.prefix()).isEqualTo("order:create");
        // P2-1：键必须带 userId，避免空/通用 token 退化成全局共享键
        assertThat(idempotent.key()).isEqualTo("#userId + ':' + #request.clientToken");
    }

    // ------------------------------------------------------------------
    // B7：运费服务端取价——Feign 失败即建单失败；客户端上送值忽略（篡改 warn）
    // ------------------------------------------------------------------

    @Test
    void create_freightCalcFails_failsCreate_noSilentZeroFreight() {
        CreateOrderRequest req = normalRequest();
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(productClient.calcFreight(any()))
                .thenReturn(Result.fail(ErrorCode.DEPENDENCY_FAIL, "freight service down"));

        assertThatThrownBy(() -> service.create(req, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.DEPENDENCY_FAIL.getCode());
        verify(productClient, never()).lockStock(any());
        verify(marketingClient, never()).calculate(any());
    }

    @Test
    void create_clientFreightTampered_serverValueUsedInCalc() {
        CreateOrderRequest req = normalRequest();
        req.setFreightFen(99999L); // 客户端篡改
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(productClient.calcFreight(any())).thenReturn(Result.success(
                FreightCalcResponse.builder().freightFen(800L).snapshotJson("{}").build()));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20800L, 20800L, 0L)));
        stubLockSuccess();

        service.create(req, USER_ID);

        // 营销试算收到的运费恒为服务端试算值，客户端 99999 不参与
        ArgumentCaptor<PriceCalcCommand> captor = ArgumentCaptor.forClass(PriceCalcCommand.class);
        verify(marketingClient).calculate(captor.capture());
        assertThat(captor.getValue().getFreightFen()).isEqualTo(800L);
    }

    // ------------------------------------------------------------------
    // FUNDS B11：运费险勾选/不勾选的金额链路
    // ------------------------------------------------------------------

    @Test
    void create_buyFreightInsurance_payFenIncludesPremium_orderFieldsSet() {
        CreateOrderRequest req = normalRequest();
        req.setBuyFreightInsurance(Boolean.TRUE);
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 20000L, 0L)));
        stubLockSuccess();

        service.create(req, USER_ID);

        ArgumentCaptor<OrderAggregate> captor = ArgumentCaptor.forClass(OrderAggregate.class);
        verify(orderPersister).persist(captor.capture(),
                any(OrderCreatedEvent.class), any(OrderDelayMessage.class));
        Order order = captor.getValue().getOrder();
        // payFen 恰好多 100 分保费；支付域反查订单取 payFen 收款（金额天然含保费）
        assertThat(order.getPayFen()).isEqualTo(20100L);
        assertThat(order.getHasFreightInsurance()).isEqualTo(1);
        assertThat(order.getInsurancePremiumFen()).isEqualTo(100L);
    }

    @Test
    void create_noFreightInsurance_amountsUnchanged() {
        CreateOrderRequest req = normalRequest();
        req.setBuyFreightInsurance(Boolean.FALSE);
        stubPrechecks(req, sku(11L, 4001L, 10000L, GoodsStatuses.ON_SALE.getCode(), 100L));
        when(marketingClient.calculate(any()))
                .thenReturn(Result.success(calcResult(20000L, 20000L, 0L)));
        stubLockSuccess();

        service.create(req, USER_ID);

        ArgumentCaptor<OrderAggregate> captor = ArgumentCaptor.forClass(OrderAggregate.class);
        verify(orderPersister).persist(captor.capture(),
                any(OrderCreatedEvent.class), any(OrderDelayMessage.class));
        Order order = captor.getValue().getOrder();
        assertThat(order.getPayFen()).isEqualTo(20000L);
        assertThat(order.getHasFreightInsurance()).isEqualTo(0);
        assertThat(order.getInsurancePremiumFen()).isZero();
    }
}
