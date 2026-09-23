package com.shop.order.order.service.impl;

import com.shop.api.marketing.client.MarketingClient;
import com.shop.api.marketing.dto.CalcItem;
import com.shop.api.marketing.dto.ItemPriceDetail;
import com.shop.api.marketing.dto.PriceCalcCommand;
import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.api.marketing.dto.PromotionLockCommand;
import com.shop.api.order.enums.OrderStatuses;
import com.shop.api.order.enums.OrderTypes;
import com.shop.api.order.event.OrderCreatedEvent;
import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.FreightCalcRequest;
import com.shop.api.product.dto.FreightCalcResponse;
import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.dto.StockItemCommand;
import com.shop.api.product.dto.StockLockCommand;
import com.shop.api.product.enums.GoodsStatuses;
import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.AddressDTO;
import com.shop.api.user.dto.PointsLockCommand;
import com.shop.api.user.dto.UserDTO;
import com.shop.api.user.dto.UserLevelDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shop.framework.idempotent.Idempotent;
import com.shop.order.idgen.OrderNoGenerator;
import com.shop.order.invoice.entity.OrderInvoice;
import com.shop.order.mq.message.OrderDelayMessage;
import com.shop.order.order.dto.CreateOrderRequest;
import com.shop.order.order.dto.InvoiceRequest;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.service.OrderAggregate;
import com.shop.order.order.service.OrderCreateService;
import com.shop.order.order.service.OrderPersister;
import com.shop.order.policy.PayTimeoutPolicy;
import com.shop.order.support.FeignResults;
import com.shop.order.support.FreightInsuranceCalculator;
import com.shop.order.support.OrderAssembler;
import com.shop.order.support.PurchaseLimitChecker;
import com.shop.order.support.RegionDeliveryChecker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 下单核心编排（design 5.3）。
 *
 * <p>8 项前置校验 → 生成订单号 → lockStock → lockPromotion → lockPoints → 同事务落单 →
 * 提交后发 ORDER_CREATED + 支付超时延时消息。任一步失败对已成功的 try 步骤逆序补偿，
 * 保证无脏单；{@code clientToken} 经 {@link Idempotent} 防重复提交。
 */
@Service
public class OrderCreateServiceImpl implements OrderCreateService {

    private static final Logger log = LoggerFactory.getLogger(OrderCreateServiceImpl.class);

    // ---- O6 下单指标（命名冻结；标签仅 result/channel，严禁 orderNo/userId 高基数字段） ----
    static final String ORDER_CREATED_TOTAL = "shop_order_created_total";
    static final String ORDER_CREATE_SECONDS = "shop_order_create_seconds";
    static final String RESULT_SUCCESS = "success";
    static final String RESULT_FAIL = "fail";
    /** 订单来源 source：1 APP 2 H5 3 小程序 4 PC；缺省/越界统一 UNKNOWN，基数有界。 */
    static final String CHANNEL_APP = "APP";
    static final String CHANNEL_H5 = "H5";
    static final String CHANNEL_MINI = "MINI";
    static final String CHANNEL_WEB = "WEB";
    static final String CHANNEL_UNKNOWN = "UNKNOWN";

    /** 积分抵现单笔封顶商品金额 50%（design 2.2.2） */
    private static final long POINTS_DEDUCT_CAP_BPS = 5000L;
    private static final long BPS_BASE = 10000L;
    /** 积分与分的换算：100 积分 = 100 分 = 1 元，即 1 积分 = 1 分 */
    private static final long POINTS_TO_FEN = 1L;

    private final ProductClient productClient;
    private final MarketingClient marketingClient;
    private final UserClient userClient;
    private final OrderItemMapper orderItemMapper;
    private final OrderNoGenerator orderNoGenerator;
    private final OrderPersister orderPersister;
    private final PurchaseLimitChecker purchaseLimitChecker;
    private final RegionDeliveryChecker regionDeliveryChecker;
    private final PayTimeoutPolicy payTimeoutPolicy;
    private final OrderAssembler assembler;
    private final FreightInsuranceCalculator freightInsuranceCalculator;
    /** 无 MeterRegistry 环境（部分单测）为 null，全部埋点空转安全。 */
    private final MeterRegistry meterRegistry;

    /** 单构造器；required=false 使无 MeterRegistry Bean 的切片环境仍可装配（O6）。 */
    @Autowired(required = false)
    public OrderCreateServiceImpl(ProductClient productClient, MarketingClient marketingClient,
                                  UserClient userClient, OrderItemMapper orderItemMapper,
                                  OrderNoGenerator orderNoGenerator, OrderPersister orderPersister,
                                  PurchaseLimitChecker purchaseLimitChecker,
                                  RegionDeliveryChecker regionDeliveryChecker,
                                  PayTimeoutPolicy payTimeoutPolicy, OrderAssembler assembler,
                                  FreightInsuranceCalculator freightInsuranceCalculator,
                                  MeterRegistry meterRegistry) {
        this.productClient = productClient;
        this.marketingClient = marketingClient;
        this.userClient = userClient;
        this.orderItemMapper = orderItemMapper;
        this.orderNoGenerator = orderNoGenerator;
        this.orderPersister = orderPersister;
        this.purchaseLimitChecker = purchaseLimitChecker;
        this.regionDeliveryChecker = regionDeliveryChecker;
        this.payTimeoutPolicy = payTimeoutPolicy;
        this.assembler = assembler;
        this.freightInsuranceCalculator = freightInsuranceCalculator;
        this.meterRegistry = meterRegistry;
    }

    @Override
    /**
     * 幂等键必须含 userId：同一 clientToken 只在同一用户命名空间内防重，
     * 避免空/通用 token 在全局共用一个 redis 键误伤其他用户（P2-1）。
     */
    @Idempotent(prefix = "order:create", key = "#userId + ':' + #request.clientToken")
    public String create(CreateOrderRequest request, Long userId) {
        // O6：channel 为受控枚举（source 1-4 → APP/H5/MINI/WEB，缺省 UNKNOWN），禁止原值上标签。
        String channel = channelOf(request);
        long startNano = System.nanoTime();
        try {
            String orderNo = doCreate(request, userId);
            recordCreate(RESULT_SUCCESS, channel, startNano);
            return orderNo;
        } catch (RuntimeException e) {
            // 含全部 BizException 路径（前置校验/TCC 失败补偿后重抛）；系统异常同样计 fail
            recordCreate(RESULT_FAIL, channel, startNano);
            throw e;
        }
    }

    private String doCreate(CreateOrderRequest request, Long userId) {
        // 校验 1：登录态
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        // 双保险：非 HTTP 入口（内部调用/单测）绕过 @Valid 时也不允许空 token 进入幂等切面
        if (request == null || request.getClientToken() == null || request.getClientToken().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "clientToken 不能为空");
        }
        PreparedContext ctx = prepare(request, userId);
        String orderNo = orderNoGenerator.next(request.getOrderType(), userId);

        boolean stockLocked = false;
        boolean promotionLocked = false;
        boolean pointsLocked = false;
        OrderAggregate aggregate = null;
        try {
            // 步骤 ①：锁定库存（TCC-try）
            FeignResults.unwrap(productClient.lockStock(StockLockCommand.builder()
                    .orderNo(orderNo)
                    .orderType(request.getOrderType())
                    .items(buildStockItems(request, ctx))
                    .build()));
            stockLocked = true;

            // 步骤 ②：预核销券 / 锁秒杀、拼团、预售资源
            FeignResults.unwrap(marketingClient.lockPromotion(PromotionLockCommand.builder()
                    .userId(userId)
                    .orderNo(orderNo)
                    .orderType(request.getOrderType())
                    .items(ctx.calcItems)
                    .userCouponIds(ctx.calcResult.getUsedUserCouponIds())
                    .activityId(resolveActivityId(request))
                    .snapshotJson(ctx.calcResult.getSnapshotJson())
                    .build()));
            promotionLocked = true;

            // 步骤 ③：积分预扣（有用积分时；不足由用户域拒绝）
            long pointsDeductFen = nz(ctx.calcResult.getPointsDeductFen());
            if (pointsDeductFen > 0) {
                FeignResults.unwrap(userClient.lockPoints(PointsLockCommand.builder()
                        .userId(userId)
                        .bizNo(orderNo)
                        .points(pointsDeductFen * POINTS_TO_FEN)
                        .deductFen(pointsDeductFen)
                        .build()));
                pointsLocked = true;
            }

            // 步骤 ④：同事务落订单 + 明细 + 发票 + outbox（ORDER_CREATED / 支付超时延时）。
            // 事件行与业务数据同提交：发送由 OutboxRelayJob 保证，无需提交后即时发 MQ，
            // 也不存在「单已落、事件丢」后需要前滚关单的窗口。
            aggregate = buildAggregate(orderNo, request, userId, ctx, pointsLocked);
            orderPersister.persist(aggregate, buildCreatedEvent(aggregate), buildPayTimeoutMessage(aggregate));
        } catch (Exception e) {
            // 任一步失败：逆序补偿（积分 → 营销 → 库存），无脏单
            compensate(orderNo, userId, request, ctx, pointsLocked, promotionLocked, stockLocked, e);
            throw e instanceof BizException biz ? biz
                    : new BizException(ErrorCode.SYSTEM_ERROR, "下单失败：" + e.getMessage(), e);
        }
        return orderNo;
    }

    // ------------------------------------------------------------------
    // 8 项前置校验（5.3.1）
    // ------------------------------------------------------------------

    private PreparedContext prepare(CreateOrderRequest req, Long userId) {
        PreparedContext ctx = new PreparedContext();
        // 合并同 SKU 行
        Map<Long, Integer> qtyMap = new LinkedHashMap<>();
        for (CreateOrderRequest.Item item : req.getItems()) {
            qtyMap.merge(item.getSkuId(), item.getQty(), Integer::sum);
        }
        ctx.qtyMap = qtyMap;

        // 校验 2：商品状态（已上架、非违规/删除）
        List<SkuDTO> skus = FeignResults.unwrap(productClient.listSkus(new ArrayList<>(qtyMap.keySet())));
        Map<Long, SkuDTO> skuMap = new LinkedHashMap<>();
        if (skus != null) {
            for (SkuDTO sku : skus) {
                skuMap.put(sku.getSkuId(), sku);
            }
        }
        long originalProductFen = 0L;
        List<CalcItem> calcItems = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : qtyMap.entrySet()) {
            SkuDTO sku = skuMap.get(e.getKey());
            if (sku == null) {
                throw new BizException(ErrorCode.GOODS_NOT_SALE, "商品不存在或已删除: " + e.getKey());
            }
            if (sku.getStatus() == null || sku.getStatus() != GoodsStatuses.ON_SALE.getCode()) {
                throw new BizException(ErrorCode.GOODS_NOT_SALE,
                        "商品「" + sku.getSkuName() + "」当前不可售（状态 " + sku.getStatus() + "）");
            }
            // 校验 3：库存（可售库存 ≥ 购买数量）
            Boolean saleable = FeignResults.unwrap(productClient.saleable(sku.getSkuId(), e.getValue()));
            if (!Boolean.TRUE.equals(saleable)) {
                throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                        "商品「" + sku.getSkuName() + "」库存不足");
            }
            // 校验 4：限购（历史成交量 + 本次 ≤ N）
            long purchased = orderItemMapper.sumPurchasedQty(userId, sku.getSkuId());
            purchaseLimitChecker.check(sku.getSkuId(), e.getValue(), purchased);

            long unitPrice = resolveUnitPrice(req.getOrderType(), sku);
            originalProductFen += unitPrice * e.getValue();
            calcItems.add(CalcItem.builder()
                    .skuId(sku.getSkuId())
                    .spuId(sku.getSpuId())
                    .merchantId(sku.getMerchantId())
                    .shopId(sku.getShopId())
                    .category3Id(sku.getCategory3Id())
                    .qty(e.getValue())
                    .salePriceFen(unitPrice)
                    .build());
        }
        ctx.skus = skuMap;
        ctx.calcItems = calcItems;

        // 一单一店铺校验：本次下单商品必须属于同一店铺
        long distinctShop = skuMap.values().stream().map(SkuDTO::getShopId).distinct().count();
        if (distinctShop > 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不同店铺的商品请分别下单");
        }

        // 校验 5：区域配送（地址存在、归属用户、信息完整；默认全国可配送，校验点保留）
        AddressDTO address = FeignResults.unwrap(userClient.getAddress(req.getAddressId()));
        regionDeliveryChecker.check(address, userId);
        ctx.address = address;

        // B7：运费服务端取价为唯一权威（C32）。忽略客户端上送 freightFen，篡改仅 warn 不报错；
        // 试算失败（含 null 响应）即建单失败，禁止静默按 0 运费落单。
        List<FreightCalcRequest.FreightItem> freightItems = qtyMap.entrySet().stream()
                .map(e -> FreightCalcRequest.FreightItem.builder()
                        .skuId(e.getKey()).qty(e.getValue()).build())
                .toList();
        FreightCalcResponse freight = FeignResults.unwrap(productClient.calcFreight(
                FreightCalcRequest.builder()
                        .addressId(req.getAddressId())
                        .items(freightItems)
                        .build()));
        if (freight == null || freight.getFreightFen() == null || freight.getFreightFen() < 0) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL, "运费试算结果非法，建单失败");
        }
        long serverFreightFen = freight.getFreightFen();
        long clientFreightFen = nz(req.getFreightFen());
        if (clientFreightFen != serverFreightFen) {
            // 兼容老端：不拒绝、不采信，仅留痕
            log.warn("客户端上送运费与服务端试算不一致，忽略客户端值 userId={} clientFreightFen={} "
                            + "serverFreightFen={} addressId={}",
                    userId, clientFreightFen, serverFreightFen, req.getAddressId());
        }
        ctx.serverFreightFen = serverFreightFen;

        // 用户快照 + 会员等级（营销试算需要等级）
        UserDTO user = FeignResults.unwrap(userClient.getUser(userId));
        UserLevelDTO level = FeignResults.unwrap(userClient.getLevel(userId));
        ctx.user = user;
        ctx.userLevel = level == null ? 0 : level.getLevel();

        // 积分 50% 封顶前置校验（最终以营销试算结果与用户域 lockPoints 为准）
        long requestPointsFen = nz(req.getUsePointsFen());
        if (requestPointsFen > 0) {
            long cap = originalProductFen * POINTS_DEDUCT_CAP_BPS / BPS_BASE;
            if (requestPointsFen > cap) {
                throw new BizException(ErrorCode.POINTS_NOT_ENOUGH,
                        "积分抵现金额不能超过商品金额的 50%");
            }
        }

        // 校验 6/7：营销活动进行中 + 券可用（calculate 为试算，活动结束/券失效由营销域拒绝）
        PriceCalcCommand calcCommand = PriceCalcCommand.builder()
                .userId(userId)
                .userLevel(ctx.userLevel)
                .orderType(req.getOrderType())
                .items(calcItems)
                .freightFen(ctx.serverFreightFen)
                .usePointsFen(requestPointsFen)
                .categoryCouponId(req.getCategoryCouponId())
                .shopCouponId(req.getShopCouponId())
                .platformCouponId(req.getPlatformCouponId())
                .seckillActivityId(req.getSeckillActivityId())
                .groupbuyActivityId(req.getGroupbuyActivityId())
                .presaleActivityId(req.getPresaleActivityId())
                .presaleFinalStage(req.getPresaleFinalStage())
                .build();
        PriceCalcResult result;
        try {
            result = FeignResults.unwrap(marketingClient.calculate(calcCommand));
        } catch (BizException e) {
            // 券类错误码透传，其余活动/互斥问题归为活动不可用
            if (e.getCode() == ErrorCode.COUPON_NOT_AVAILABLE.getCode()
                    || e.getCode() == ErrorCode.COUPON_LIMIT.getCode()) {
                throw e;
            }
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE,
                    "营销活动校验失败：" + e.getMessage(), e);
        }
        if (result == null || result.getPayFen() == null || result.getPayFen() < 0) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "营销试算结果非法");
        }
        // B1 C37：拼团团长价由营销试算服务端裁定（leaderPriceFen/leaderFlag），客户端不参与定价；
        // 非空时写入价格快照，随订单落库，支付/售后均以此为准。
        if (result.getLeaderPriceFen() != null) {
            result.setSnapshotJson(withLeaderPrice(result.getSnapshotJson(),
                    result.getLeaderPriceFen(), result.getLeaderFlag()));
        }
        // FUNDS B11：运费险保费服务端计算（固定保费，不允许券/积分抵扣），勾选则计入 payFen。
        // 支付域 C 端 /pays 反查订单取 payFen 作为收款金额，含保费金额天然透传。
        long insurancePremiumFen = freightInsuranceCalculator.premiumFen(req, result);
        if (insurancePremiumFen > 0) {
            result.setInsurancePremiumFen(insurancePremiumFen);
            result.setPayFen(nz(result.getPayFen()) + insurancePremiumFen);
        }
        ctx.insurancePremiumFen = insurancePremiumFen;
        ctx.calcResult = result;

        // 校验 7（补充）：用户选中的券必须全部在试算生效列表中
        List<Long> requestedCoupons = nonNullList(req.getCategoryCouponId(),
                req.getShopCouponId(), req.getPlatformCouponId());
        List<Long> usedCoupons = result.getUsedUserCouponIds() == null
                ? List.of() : result.getUsedUserCouponIds();
        for (Long couponId : requestedCoupons) {
            if (!usedCoupons.contains(couponId)) {
                throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE,
                        "优惠券 " + couponId + " 不可用或已过期");
            }
        }
        // 校验 8：积分（用户余额不足在 lockPoints 由用户域拒绝，失败触发逆序补偿）
        return ctx;
    }

    // ------------------------------------------------------------------
    // 聚合组装
    // ------------------------------------------------------------------

    private OrderAggregate buildAggregate(String orderNo, CreateOrderRequest req, Long userId,
                                          PreparedContext ctx, boolean pointsLocked) {
        PriceCalcResult r = ctx.calcResult;
        SkuDTO firstSku = ctx.skus.values().iterator().next();
        LocalDateTime now = LocalDateTime.now();

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setUserNickname(ctx.user == null ? "" : nz(ctx.user.getNickname()));
        order.setUserPhone(ctx.address.getPhone());
        order.setMerchantId(firstSku.getMerchantId());
        order.setShopId(firstSku.getShopId());
        order.setOrderType(req.getOrderType());
        order.setStatus(OrderStatuses.WAIT_PAY);
        order.setSource(req.getSource() == null ? 1 : req.getSource());

        order.setProductTotalFen(nz(r.getOriginalProductFen()));
        order.setFreightFen(nz(r.getFreightFen()));
        order.setProductDiscountFen(nz(r.getProductPromoFen()));
        order.setShopDiscountFen(nz(r.getShopPromoFen()) + nz(r.getShopCouponFen()));
        order.setPlatformDiscountFen(nz(r.getCategoryCouponFen()) + nz(r.getPlatformCouponFen()));
        order.setPointsDeductFen(nz(r.getPointsDeductFen()));
        order.setDiscountTotalFen(nz(r.getProductPromoFen()) + nz(r.getShopPromoFen())
                + nz(r.getCategoryCouponFen()) + nz(r.getShopCouponFen()) + nz(r.getPlatformCouponFen())
                + nz(r.getFreightCouponFen()) + nz(r.getPointsDeductFen()));
        order.setPayFen(nz(r.getPayFen()));
        order.setUsedPoints(nz(r.getPointsDeductFen()));
        // FUNDS B11：运费险两列（保费已含在 payFen 内，取消/超时未支付无收款故无退款动作）
        order.setHasFreightInsurance(ctx.insurancePremiumFen > 0 ? 1 : 0);
        order.setInsurancePremiumFen(ctx.insurancePremiumFen);
        List<Long> usedCouponIds = r.getUsedUserCouponIds();
        order.setUserCouponId(usedCouponIds != null && !usedCouponIds.isEmpty() ? usedCouponIds.get(0) : null);

        order.setReceiver(ctx.address.getReceiver());
        order.setReceiverPhone(ctx.address.getPhone());
        order.setProvince(ctx.address.getProvince());
        order.setCity(ctx.address.getCity());
        order.setDistrict(ctx.address.getDistrict());
        order.setDetailAddress(ctx.address.getDetailAddress());
        order.setAddressId(req.getAddressId());
        order.setRemark(nz(req.getRemark()));
        order.setPriceSnapshot(r.getSnapshotJson());

        order.setSeckillActivityId(req.getSeckillActivityId());
        order.setGroupbuyActivityId(req.getGroupbuyActivityId());
        order.setGroupNo(req.getGroupNo());
        order.setPresaleActivityId(req.getPresaleActivityId());
        order.setPresaleFinalStage(Boolean.TRUE.equals(req.getPresaleFinalStage()) ? 1 : 0);

        long expireSeconds = payTimeoutPolicy.expirePaySeconds(req.getOrderType());
        order.setExpireTime(now.plusSeconds(expireSeconds));

        List<OrderItem> items = buildOrderItems(orderNo, req, userId, ctx);

        OrderInvoice invoice = buildInvoice(orderNo, req.getInvoice(), userId,
                firstSku.getMerchantId());

        return OrderAggregate.builder()
                .order(order)
                .items(items)
                .invoice(invoice)
                .expirePaySeconds(expireSeconds)
                .usedCouponIds(usedCouponIds)
                .pointsLocked(pointsLocked)
                .cartIdsToClear(req.getFromCartIds())
                .build();
    }

    private List<OrderItem> buildOrderItems(String orderNo, CreateOrderRequest req, Long userId,
                                            PreparedContext ctx) {
        Map<Long, ItemPriceDetail> detailMap = new LinkedHashMap<>();
        if (ctx.calcResult.getItemDetails() != null) {
            for (ItemPriceDetail d : ctx.calcResult.getItemDetails()) {
                detailMap.put(d.getSkuId(), d);
            }
        }
        List<OrderItem> list = new ArrayList<>(ctx.qtyMap.size());
        int stockType = stockTypeOf(req.getOrderType());
        Long activityId = resolveActivityId(req);
        for (Map.Entry<Long, Integer> e : ctx.qtyMap.entrySet()) {
            SkuDTO sku = ctx.skus.get(e.getKey());
            long unitPrice = resolveUnitPrice(req.getOrderType(), sku);
            ItemPriceDetail d = detailMap.get(e.getKey());
            OrderItem item = new OrderItem();
            item.setOrderNo(orderNo);
            item.setUserId(userId);
            item.setSkuId(sku.getSkuId());
            item.setSpuId(sku.getSpuId());
            item.setMerchantId(sku.getMerchantId());
            item.setShopId(sku.getShopId());
            item.setCategory3Id(sku.getCategory3Id());
            item.setSkuName(sku.getSkuName());
            item.setSpecText(sku.getSpecText());
            item.setImage(sku.getImage());
            item.setPriceFen(unitPrice);
            item.setQty(e.getValue());
            item.setStockType(stockType);
            item.setActivityId(activityId);
            item.setSeckillActivityId(req.getOrderType() == OrderTypes.SECKILL ? req.getSeckillActivityId() : null);
            item.setGroupNo(req.getOrderType() == OrderTypes.GROUPBUY ? req.getGroupNo() : null);
            item.setPresaleActivityId(req.getOrderType() == OrderTypes.PRESALE ? req.getPresaleActivityId() : null);
            item.setAftersaleStatus(0);
            item.setRefundedFen(0L);
            if (d != null) {
                item.setItemTotalFen(nz(d.getOriginalFen()));
                item.setDiscountAllocFen(nz(d.getProductPromoFen()) + nz(d.getShopPromoFen())
                        + nz(d.getCouponAllocFen()));
                item.setPointsAllocFen(nz(d.getPointsAllocFen()));
                item.setFreightAllocFen(nz(d.getFreightAllocFen()));
                item.setPaidFen(nz(d.getPaidFen()));
            } else {
                long total = unitPrice * e.getValue();
                item.setItemTotalFen(total);
                item.setDiscountAllocFen(0L);
                item.setPointsAllocFen(0L);
                item.setFreightAllocFen(0L);
                item.setPaidFen(total);
            }
            list.add(item);
        }
        return list;
    }

    private OrderInvoice buildInvoice(String orderNo, InvoiceRequest req, Long userId, Long merchantId) {
        if (req == null || req.getInvoiceType() == null || req.getInvoiceType() == 0) {
            return null;
        }
        if ("COMPANY".equals(req.getTitleType())) {
            if (isBlank(req.getCompanyName()) || isBlank(req.getTaxNo())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "企业发票必须填写公司名称与税号");
            }
        }
        if (req.getInvoiceType() == 1 && isBlank(req.getEmail())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "电子发票必须填写接收邮箱");
        }
        OrderInvoice invoice = new OrderInvoice();
        invoice.setOrderNo(orderNo);
        invoice.setUserId(userId);
        invoice.setMerchantId(merchantId);
        invoice.setInvoiceType(req.getInvoiceType());
        invoice.setContentScope(req.getContentScope() == null ? 1 : req.getContentScope());
        invoice.setTitleType(isBlank(req.getTitleType()) ? "PERSONAL" : req.getTitleType());
        invoice.setCompanyName(nz(req.getCompanyName()));
        invoice.setTaxNo(nz(req.getTaxNo()));
        invoice.setEmail(nz(req.getEmail()));
        invoice.setStatus(0);
        return invoice;
    }

    private List<StockItemCommand> buildStockItems(CreateOrderRequest req, PreparedContext ctx) {
        int stockType = stockTypeOf(req.getOrderType());
        Long activityId = resolveActivityId(req);
        return ctx.qtyMap.entrySet().stream()
                .map(e -> StockItemCommand.builder()
                        .skuId(e.getKey())
                        .qty(e.getValue())
                        .stockType(stockType)
                        .activityId(activityId)
                        .build())
                .toList();
    }

    // ------------------------------------------------------------------
    // 事件 / 补偿
    // ------------------------------------------------------------------

    private OrderCreatedEvent buildCreatedEvent(OrderAggregate aggregate) {
        Order o = aggregate.getOrder();
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderNo(o.getOrderNo())
                .userId(o.getUserId())
                .orderType(o.getOrderType())
                .status(OrderStatuses.WAIT_PAY)
                .usedPointsFen(o.getPointsDeductFen())
                .userCouponId(o.getUserCouponId())
                .freightFen(o.getFreightFen())
                .expirePaySeconds(aggregate.getExpirePaySeconds())
                .items(assembler.toMessages(aggregate.getItems()))
                .build();
        event.setBizNo(o.getOrderNo());
        return event;
    }

    private OrderDelayMessage buildPayTimeoutMessage(OrderAggregate aggregate) {
        OrderDelayMessage timeout = OrderDelayMessage.builder()
                .orderNo(aggregate.getOrder().getOrderNo()).build();
        timeout.setBizNo(aggregate.getOrder().getOrderNo());
        return timeout;
    }

    private void compensate(String orderNo, Long userId, CreateOrderRequest req, PreparedContext ctx,
                            boolean pointsLocked, boolean promotionLocked, boolean stockLocked,
                            Exception cause) {
        log.warn("下单失败触发补偿 orderNo={} stockLocked={} promotionLocked={} pointsLocked={}",
                orderNo, stockLocked, promotionLocked, pointsLocked, cause);
        // 逆序：积分 → 营销 → 库存；各 release 以 orderNo 幂等
        if (pointsLocked) {
            try {
                userClient.releasePoints(com.shop.api.user.dto.PointsReleaseCommand.builder()
                        .userId(userId).bizNo(orderNo).build());
            } catch (Exception ex) {
                log.error("补偿释放积分失败 orderNo={}", orderNo, ex);
            }
        }
        if (promotionLocked) {
            try {
                marketingClient.releasePromotion(com.shop.api.marketing.dto.PromotionReleaseCommand
                        .builder().userId(userId).orderNo(orderNo).build());
            } catch (Exception ex) {
                log.error("补偿释放营销资源失败 orderNo={}", orderNo, ex);
            }
        }
        if (stockLocked) {
            try {
                List<StockItemCommand> stockItems = ctx == null || ctx.qtyMap == null
                        ? List.of()
                        : ctx.qtyMap.entrySet().stream()
                        .map(e -> StockItemCommand.builder()
                                .skuId(e.getKey()).qty(e.getValue())
                                .stockType(stockTypeOf(req.getOrderType()))
                                .activityId(resolveActivityId(req))
                                .build())
                        .toList();
                productClient.releaseStock(com.shop.api.product.dto.StockReleaseCommand.builder()
                        .orderNo(orderNo).orderType(req.getOrderType()).items(stockItems).build());
            } catch (Exception ex) {
                log.error("补偿释放库存失败 orderNo={}", orderNo, ex);
            }
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private long resolveUnitPrice(Integer orderType, SkuDTO sku) {
        if (orderType == OrderTypes.SECKILL && sku.getSeckillPriceFen() != null
                && sku.getSeckillPriceFen() > 0) {
            return sku.getSeckillPriceFen();
        }
        return sku.getSalePriceFen() == null ? 0L : sku.getSalePriceFen();
    }

    private Long resolveActivityId(CreateOrderRequest req) {
        return switch (req.getOrderType()) {
            case OrderTypes.SECKILL -> req.getSeckillActivityId();
            case OrderTypes.GROUPBUY -> req.getGroupbuyActivityId();
            case OrderTypes.PRESALE -> req.getPresaleActivityId();
            default -> null;
        };
    }

    private int stockTypeOf(Integer orderType) {
        return switch (orderType) {
            case OrderTypes.SECKILL -> 3;
            case OrderTypes.GROUPBUY -> 4;
            case OrderTypes.PRESALE -> 2;
            default -> 1;
        };
    }

    @SafeVarargs
    private final List<Long> nonNullList(Long... ids) {
        List<Long> list = new ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id != null) {
                    list.add(id);
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------
    // O6 指标
    // ------------------------------------------------------------------

    private static String channelOf(CreateOrderRequest req) {
        if (req == null || req.getSource() == null) {
            return CHANNEL_UNKNOWN;
        }
        return switch (req.getSource()) {
            case 1 -> CHANNEL_APP;
            case 2 -> CHANNEL_H5;
            case 3 -> CHANNEL_MINI;
            case 4 -> CHANNEL_WEB;
            default -> CHANNEL_UNKNOWN;
        };
    }

    /** 成功/失败计数 + 主流程耗时；无注册表时空转，绝不因埋点影响下单。 */
    private void recordCreate(String result, String channel, long startNano) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(ORDER_CREATED_TOTAL, "result", result, "channel", channel).increment();
        Timer.builder(ORDER_CREATE_SECONDS)
                .tags("channel", channel)
                .register(meterRegistry)
                .record(System.nanoTime() - startNano, TimeUnit.NANOSECONDS);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 将营销试算裁定的拼团团长价（leaderPriceFen/leaderFlag）并入价格快照 JSON。
     * 快照约定为 JSON 对象；解析失败/非对象时用包装对象兜底，保证字段可追溯且不丢原快照。
     */
    private String withLeaderPrice(String snapshotJson, Long leaderPriceFen, Boolean leaderFlag) {
        try {
            String raw = isBlank(snapshotJson) ? "{}" : snapshotJson;
            JsonNode parsed = JsonUtils.mapper().readTree(raw);
            ObjectNode node = parsed instanceof ObjectNode objectNode
                    ? objectNode
                    : JsonUtils.mapper().createObjectNode().set("rawSnapshot", parsed.deepCopy());
            node.put("leaderPriceFen", leaderPriceFen);
            if (leaderFlag != null) {
                node.put("leaderFlag", leaderFlag);
            }
            return JsonUtils.mapper().writeValueAsString(node);
        } catch (Exception e) {
            // 快照异常不应阻断建单（营销侧才是快照生产方）：保底输出可追溯的最小对象
            log.warn("拼团团长价写入价格快照失败，使用兜底快照 leaderPriceFen={}", leaderPriceFen, e);
            ObjectNode fallback = JsonUtils.mapper().createObjectNode();
            fallback.put("rawSnapshot", snapshotJson == null ? "" : snapshotJson);
            fallback.put("leaderPriceFen", leaderPriceFen);
            if (leaderFlag != null) {
                fallback.put("leaderFlag", leaderFlag);
            }
            return fallback.toString();
        }
    }

    /** 前置校验中间数据。 */
    private static final class PreparedContext {
        private Map<Long, Integer> qtyMap;
        private Map<Long, SkuDTO> skus;
        private List<CalcItem> calcItems;
        private AddressDTO address;
        private UserDTO user;
        private Integer userLevel;
        private PriceCalcResult calcResult;
        /** 产品域运费试算结果（分），服务端权威，客户端上送值不参与。 */
        private long serverFreightFen;
        /** 运费险保费（分），勾选时 >0 且已计入 calcResult.payFen。 */
        private long insurancePremiumFen;
    }
}
