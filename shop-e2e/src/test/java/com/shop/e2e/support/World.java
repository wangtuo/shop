package com.shop.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/**
 * 跨场景共享的测试世界：统一登录、造商品/券/活动、下单支付、售后流转、清算查询等黑盒流程。
 *
 * <p>懒加载、进程内单例；所有数据自带唯一后缀，场景之间不依赖执行顺序。
 * 平台管理员 / 商户账号无法通过公开 HTTP 自助开通（见 E2E_REPORT.md 缺口 #1），
 * 通过系统属性注入；缺失时调用 {@link #assumeReady()} 的测试类整体 skip。</p>
 */
public final class World {

    // ---- 网关外部端点（StripPrefix=2 后的服务内路径） ----
    public static final String USER = "/api/user";
    public static final String PRODUCT = "/api/product";
    public static final String MARKETING = "/api/marketing";
    public static final String ORDER = "/api/order";
    public static final String PAY = "/api/pay";
    public static final String SETTLEMENT = "/api/settlement";
    public static final String AFTERSALE = "/api/aftersale";

    /** 支付方式码值：1 微信 2 支付宝 3 余额 4 银行卡 5 云闪付 6 花呗 7 白条。 */
    public static final int PAY_WECHAT = 1, PAY_ALIPAY = 2, PAY_BALANCE = 3, PAY_BANK = 4,
            PAY_UQR = 5, PAY_HUABEI = 6, PAY_BAITIAO = 7;

    /** 订单状态：10 待付款 20 待发货 30 待收货 40 已完成 50 已取消。 */
    public static final int OS_WAIT_PAY = 10, OS_WAIT_SHIP = 20, OS_WAIT_RECEIVE = 30, OS_COMPLETED = 40;

    /** 售后状态：10 待审核 20 待退货 30 商家收货中 40 退款中 41 待换货发货 42 换货已发 43 换货待收货 50 完成。 */
    public static final int AS_AUDIT = 10, AS_WAIT_RETURN = 20, AS_RECEIVING = 30, AS_REFUNDING = 40,
            AS_WAIT_EXCHANGE_SHIP = 41, AS_EXCHANGE_SHIPPED = 42, AS_EXCHANGE_WAIT_RECEIVE = 43,
            AS_FINISHED = 50, AS_REJECTED = 55;

    private static final DateTimeFormatter API_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static volatile World INSTANCE;

    public static World get() {
        if (INSTANCE == null) {
            synchronized (World.class) {
                if (INSTANCE == null) {
                    INSTANCE = new World();
                }
            }
        }
        return INSTANCE;
    }

    // ---- 身份 ----
    private ApiClient admin;
    private ApiClient merchant;
    private Long merchantId;
    private boolean merchantReady;
    private boolean adminReady;
    private Long baseCategory3Id;
    private Long baseBrandId;

    private World() {
    }

    // ==================================================================
    // 身份与账号
    // ==================================================================

    private ApiClient login(String account, String password) {
        ApiClient c = ApiClient.create();
        ApiClient.Raw raw = c.post(USER + "/auth/login", ApiClient.obj("account", account, "password", password));
        if (raw.bizCode() == 0) {
            c.withToken(raw.data().path("token").asText());
            return c;
        }
        return null;
    }

    /** 平台管理员客户端；优先用直传 token，其次账号密码（默认 admin/admin123456）。 */
    public ApiClient admin() {
        if (admin == null) {
            String direct = System.getProperty("shop.admin.token");
            if (direct != null && !direct.isBlank()) {
                admin = ApiClient.create().withToken(direct);
                adminReady = true;
            } else {
                admin = login(System.getProperty("shop.admin.account", "admin"),
                        System.getProperty("shop.admin.password", "admin123456"));
                adminReady = admin != null;
            }
        }
        return admin;
    }

    /** 商户客户端（userType=1，JWT 带 mid）。默认 merchant/merchant123456。 */
    public ApiClient merchant() {
        if (merchant == null) {
            String direct = System.getProperty("shop.merchant.token");
            if (direct != null && !direct.isBlank()) {
                merchant = ApiClient.create().withToken(direct);
                merchantReady = true;
                merchantId = Long.valueOf(System.getProperty("shop.merchant.id", "0"));
            } else {
                merchant = login(System.getProperty("shop.merchant.account", "merchant"),
                        System.getProperty("shop.merchant.password", "merchant123456"));
                merchantReady = merchant != null;
                if (merchantReady) {
                    JsonNode me = merchant.get(USER + "/users/me").data();
                    merchantId = me.path("merchantId").asLong();
                }
            }
        }
        return merchant;
    }

    public long merchantId() {
        merchant();
        Assumptions.assumeTrue(merchantReady && merchantId != null && merchantId > 0,
                "缺少可用商户账号（-Dshop.merchant.account/password 或 -Dshop.merchant.token + shop.merchant.id），跳过");
        return merchantId;
    }

    /** @BeforeAll 中调用：平台/商户账号不可用时整类 skip 而不是失败。 */
    public void assumeReady() {
        Assumptions.assumeTrue(admin() != null,
                "缺少平台管理员账号（-Dshop.admin.account/password 或 -Dshop.admin.token），跳过");
        Assumptions.assumeTrue(merchant() != null,
                "缺少商户账号（-Dshop.merchant.account/password 或 -Dshop.merchant.token），跳过");
        Assumptions.assumeTrue(merchantId != null && merchantId > 0,
                "商户账号缺少 merchantId（JWT mid 为空，不是商户身份），跳过");
    }

    /** 幂等尝试商户入驻（已入驻时忽略错误），并保证有足额类目/品牌。 */
    public synchronized void ensureMerchantOnboarded() {
        long mid = merchantId();
        ObjectNode body = ApiClient.obj(
                "merchantId", mid,
                "merchantName", "E2E商户" + mid,
                "categoryId", 1L,
                "categoryName", "E2E类目",
                "commissionRateBps", 0,
                "depositRequiredFen", 100_000L);
        try {
            admin().post(SETTLEMENT + "/admin/merchants", body);
        } catch (Exception ignore) {
            // 重复入驻等冲突不影响后续（入驻状态由环境保证）
        }
        baseFixtures();
    }

    /** 注册 + 登录一个全新消费者，并建好默认收货地址。 */
    public Buyer newBuyer() {
        String username = DataFactory.uniqueUsername("e2e");
        String phone = DataFactory.uniquePhone();
        String password = "E2e@123456";
        ApiClient c = ApiClient.create();
        ApiClient.Raw reg = c.mustPost(USER + "/auth/register", ApiClient.obj(
                "username", username,
                "phone", phone,
                "password", password,
                "nickname", username), "注册消费者");
        long userId = reg.data().asLong();
        ApiClient.Raw login = c.mustPost(USER + "/auth/login",
                ApiClient.obj("account", username, "password", password), "消费者登录");
        c.withToken(login.data().path("token").asText());

        ObjectNode addr = ApiClient.obj(
                "receiver", "E2E收货人",
                "phone", phone,
                "province", "浙江省",
                "city", "杭州市",
                "district", "西湖区",
                "detailAddress", "文三路 E2E 号 " + DataFactory.seq(),
                "tag", "家",
                "isDefault", 1);
        ApiClient.Raw addrRaw = c.mustPost(USER + "/users/addresses", addr, "新建收货地址");
        long addressId = addrRaw.data().asLong();
        return new Buyer(c, userId, addressId, phone);
    }

    public record Buyer(ApiClient api, long userId, long addressId, String phone) {
    }

    // ==================================================================
    // 类目 / 品牌 / 商品
    // ==================================================================

    public synchronized long baseCategory3Id() {
        baseFixtures();
        return baseCategory3Id;
    }

    public synchronized long baseBrandId() {
        baseFixtures();
        return baseBrandId;
    }

    private void baseFixtures() {
        if (baseCategory3Id != null && baseBrandId != null) {
            return;
        }
        ApiClient a = admin();
        String suffix = String.valueOf(System.currentTimeMillis());
        long c1 = a.mustPost(PRODUCT + "/categories", ApiClient.obj(
                "name", "E2E一级" + suffix, "status", 1), "创建一级类目").data().asLong();
        long c2 = a.mustPost(PRODUCT + "/categories", ApiClient.obj(
                "pid", c1, "name", "E2E二级" + suffix, "status", 1), "创建二级类目").data().asLong();
        long c3 = a.mustPost(PRODUCT + "/categories", ApiClient.obj(
                "pid", c2, "name", "E2E三级" + suffix, "status", 1), "创建三级类目").data().asLong();
        baseCategory3Id = c3;
        ApiClient.Raw brand = a.mustPost(PRODUCT + "/brands", ApiClient.obj(
                "name", "E2E品牌" + suffix, "initial", "E", "status", 1), "创建品牌");
        baseBrandId = brand.data().asLong();
    }

    /** 商户新建 SPU+单 SKU（草稿）。 */
    public Sku createDraftSku(long priceFen, long stockQty) {
        return createDraftSku(priceFen, stockQty, null, null, 1);
    }

    public Sku createDraftSku(long priceFen, long stockQty, Long seckillPriceFen, Integer presaleFlag) {
        return createDraftSku(priceFen, stockQty, seckillPriceFen, presaleFlag, 1);
    }

    /**
     * @param stockType 1 普通 2 预售 3 秒杀 4 拼团（下单按订单类型映射独立库存池）
     */
    public Sku createDraftSku(long priceFen, long stockQty, Long seckillPriceFen, Integer presaleFlag, int stockType) {
        baseFixtures();
        merchantId();
        String code = DataFactory.skuCode();
        ObjectNode sku = ApiClient.obj(
                "skuCode", code,
                "skuName", "E2E-SKU-" + DataFactory.seq(),
                "specText", "颜色:默认;规格:均码",
                "marketPriceFen", priceFen + 1000L,
                "salePriceFen", priceFen,
                "costPriceFen", Math.max(1, priceFen / 2),
                "stockQty", stockQty,
                "stockType", stockType,
                "presaleFlag", presaleFlag == null ? 0 : presaleFlag,
                "warnThreshold", 5L);
        if (seckillPriceFen != null) {
            sku.put("seckillPriceFen", seckillPriceFen);
        }
        ObjectNode body = ApiClient.obj(
                "shopId", 1L,
                "name", DataFactory.uniqueName("E2E商品"),
                "brandId", baseBrandId,
                "category3Id", baseCategory3Id,
                "mainImage", "https://example.com/e2e.png",
                "detailJson", "{\"desc\":\"e2e\"}");
        body.set("skus", body.arrayNode().add(sku));
        JsonNode created = merchant().mustPost(PRODUCT + "/merchant/products", body, "创建SPU").data();
        // TRADE 波后建单响应由裸 id 长整型变为 {spuId, warnings} 对象；两种形态兼容
        long spuId = created.isObject() && created.hasNonNull("spuId")
                ? created.get("spuId").asLong() : created.asLong();
        // 草稿/待审核商品对公开详情不可见（30002），skuId 走本店管理详情取
        long skuId = merchant().get(PRODUCT + "/merchant/products/" + spuId)
                .data().path("skus").get(0).path("skuId").asLong();
        return new Sku(spuId, skuId, merchantId, 1L, priceFen);
    }

    /** 建商品 → 提交审核 → 平台审核通过（直接上架）。 */
    public Sku createOnSaleSku(long priceFen, long stockQty) {
        Sku sku = createDraftSku(priceFen, stockQty);
        merchant().mustPost(PRODUCT + "/merchant/products/" + sku.spuId() + "/submit",
                null, "提交审核");
        admin().mustPost(PRODUCT + "/admin/products/" + sku.spuId() + "/audit",
                ApiClient.obj("pass", true, "remark", "E2E自动审核通过"), "平台审核通过");
        return sku;
    }

    /** 带秒杀价/预售标/库存类型的商品发布链路。 */
    public Sku createOnSaleSku(long priceFen, long stockQty, Long seckillPriceFen,
                               Integer presaleFlag, int stockType) {
        Sku sku = createDraftSku(priceFen, stockQty, seckillPriceFen, presaleFlag, stockType);
        merchant().mustPost(PRODUCT + "/merchant/products/" + sku.spuId() + "/submit",
                null, "提交审核");
        admin().mustPost(PRODUCT + "/admin/products/" + sku.spuId() + "/audit",
                ApiClient.obj("pass", true, "remark", "E2E自动审核通过"), "平台审核通过");
        return sku;
    }

    public JsonNode getSpuDetail(long spuId) {
        return ApiClient.create().get(PRODUCT + "/products/" + spuId).data();
    }

    public JsonNode priceSnapshot(long skuId) {
        return ApiClient.create().get(PRODUCT + "/products/skus/" + skuId + "/price").data();
    }

    public void offSale(long spuId) {
        merchant().mustPost(PRODUCT + "/merchant/products/" + spuId + "/offsale", null, "下架");
    }

    public void replenish(long skuId, int qty) {
        merchant().mustPost(PRODUCT + "/merchant/products/skus/" + skuId + "/replenish",
                ApiClient.obj("qty", qty), "补货");
    }

    public record Sku(long spuId, long skuId, long merchantId, long shopId, long priceFen) {
    }

    // ==================================================================
    // 优惠券 / 活动
    // ==================================================================

    /** 创建一张平台无门槛立减券并上架可领；返回券模板 ID。 */
    public long createClaimableCoupon(long faceValueFen, int totalCount, int perUserLimit) {
        LocalDateTime now = LocalDateTime.now();
        ObjectNode body = ApiClient.obj(
                "name", DataFactory.uniqueName("E2E券"),
                "type", 3,                 // 无门槛券
                "scopeType", 1,            // 全场通用
                "faceValueFen", faceValueFen,
                "thresholdFen", 0L,
                "totalCount", totalCount,
                "perUserLimit", perUserLimit,
                "issueWay", 1,
                "validType", 1,
                "validStartTime", now.minusHours(1).format(API_DT),
                "validEndTime", now.plusDays(7).format(API_DT),
                "receiveStartTime", now.minusMinutes(5).format(API_DT),
                "receiveEndTime", now.plusDays(1).format(API_DT));
        long couponId = admin().mustPost(MARKETING + "/admin/coupons", body, "创建券模板").data().asLong();
        admin().mustPost(MARKETING + "/admin/coupons/" + couponId + "/status?status=1",
                null, "券上架");
        return couponId;
    }

    /** 用户从券中心领取指定券，返回用户券记录 ID；失败返回 -1（用于并发/负向断言）。 */
    public long claimCoupon(Buyer buyer, long couponId) {
        ApiClient.Raw raw = buyer.api().post(MARKETING + "/coupons/claim",
                ApiClient.obj("couponId", couponId));
        return raw.bizCode() == 0 && raw.data() != null && raw.data().isNumber() ? raw.data().asLong() : -1L;
    }

    /** 创建活动（type 10 秒杀 / 11 拼团 / 12 预售）并启用。 */
    public long createActivity(int type, String ruleJson, ArrayNode seckillSkus) {
        LocalDateTime now = LocalDateTime.now();
        ObjectNode body = ApiClient.obj(
                "name", DataFactory.uniqueName("E2E活动"),
                "type", type,
                "startTime", now.minusMinutes(2).format(API_DT),
                "endTime", now.plusDays(1).format(API_DT),
                "ruleJson", ruleJson == null ? "{}" : ruleJson);
        if (seckillSkus != null) {
            body.set("seckillSkus", seckillSkus);
        }
        long id = admin().mustPost(MARKETING + "/admin/activities", body, "创建活动").data().asLong();
        admin().mustPost(MARKETING + "/admin/activities/" + id + "/status?status=1",
                null, "活动启用");
        return id;
    }

    public long createSeckillActivity(long skuId, long seckillPriceFen, int totalStock) {
        ArrayNode arr = ApiClient.MAPPER.createArrayNode();
        arr.add(ApiClient.obj("skuId", skuId, "seckillPriceFen", seckillPriceFen, "totalStock", totalStock));
        return createActivity(10, null, arr);
    }

    public long createGroupbuyActivity() {
        // 2 人团；活动规则字段见 marketing ActivityRule
        return createActivity(11, "{\"requiredPeople\":2}", null);
    }

    public long createPresaleActivity(long depositFen, long inflateDeductFen, long finalPayFen) {
        String rule = String.format(
                "{\"depositFen\":%d,\"inflateDeductFen\":%d,\"finalPayFen\":%d,\"finalPayDays\":3}",
                depositFen, inflateDeductFen, finalPayFen);
        return createActivity(12, rule, null);
    }

    // ==================================================================
    // 订单
    // ==================================================================

    public ObjectNode baseOrderBody(Buyer buyer, Sku sku, int qty) {
        ObjectNode body = ApiClient.obj(
                "clientToken", DataFactory.clientToken(),
                "orderType", 1,
                "source", 1,
                "addressId", buyer.addressId(),
                "freightFen", 0L);
        ArrayNode items = body.arrayNode();
        items.add(ApiClient.obj("skuId", sku.skuId(), "qty", qty));
        body.set("items", items);
        return body;
    }

    /** 提交订单，返回订单号；业务失败抛 AssertionError。 */
    public String createOrder(Buyer buyer, ObjectNode body) {
        ApiClient.Raw raw = buyer.api().post(ORDER + "/orders", body);
        if (raw.bizCode() != 0) {
            throw new AssertionError("下单失败: http=" + raw.httpStatus + " body=" + raw.text);
        }
        return raw.data().asText();
    }

    /** 提交订单（允许业务失败，返回原始响应）。 */
    public ApiClient.Raw tryCreateOrder(Buyer buyer, ObjectNode body) {
        return buyer.api().post(ORDER + "/orders", body);
    }

    public JsonNode orderDetail(Buyer buyer, String orderNo) {
        return buyer.api().get(ORDER + "/orders/" + orderNo).data();
    }

    public long orderStatus(Buyer buyer, String orderNo) {
        return orderDetail(buyer, orderNo).path("status").asLong();
    }

    public long orderPayFen(Buyer buyer, String orderNo) {
        return orderDetail(buyer, orderNo).path("payFen").asLong();
    }

    public void waitOrderStatus(Buyer buyer, String orderNo, int status) {
        Poller.longTimeout().await("订单 " + orderNo + " 状态=" + status,
                () -> orderStatus(buyer, orderNo) == status);
    }

    public void shipOrder(String orderNo) {
        merchant().mustPost(ORDER + "/merchant/orders/" + orderNo + "/ship",
                ApiClient.obj("logisticsNo", DataFactory.logisticsNo(), "logisticsCompany", "E2E顺丰"),
                "商户发货");
    }

    // ==================================================================
    // 支付（mock 渠道 + HMAC 回调）
    // ==================================================================

    private static final Map<Integer, String> CHANNELS = Map.of(
            PAY_WECHAT, "MOCK_WECHAT",
            PAY_ALIPAY, "MOCK_ALIPAY",
            PAY_BANK, "MOCK_BANK",
            PAY_UQR, "MOCK_UQR",
            PAY_HUABEI, "MOCK_HUABEI",
            PAY_BAITIAO, "MOCK_BAITIAO");

    private static final Map<String, String> CHANNEL_SECRETS = Map.of(
            "MOCK_WECHAT", "mock_wechat_secret_2026",
            "MOCK_ALIPAY", "mock_alipay_secret_2026",
            "MOCK_BANK", "mock_bank_secret_2026",
            "MOCK_UQR", "mock_uqr_secret_2026",
            "MOCK_HUABEI", "mock_huabei_secret_2026",
            "MOCK_BAITIAO", "mock_baitiao_secret_2026");

    public static String channelCode(int payMethod) {
        return CHANNELS.get(payMethod);
    }

    /** 按 shop-pay SignVerifier 相同规则计算 HMAC-SHA256 签名。 */
    public static String signNotify(String channel, String notifyId, String payNo,
                                    String channelTxnNo, long amountFen, String status) {
        TreeMap<String, String> fields = new TreeMap<>();
        fields.put("amountFen", String.valueOf(amountFen));
        fields.put("channelCode", channel);
        fields.put("channelTxnNo", channelTxnNo == null ? "" : channelTxnNo);
        fields.put("notifyId", notifyId);
        fields.put("payNo", payNo);
        fields.put("status", status);
        StringBuilder canonical = new StringBuilder();
        fields.forEach((k, v) -> canonical.append(k).append('=').append(v).append('&'));
        canonical.deleteCharAt(canonical.length() - 1);
        return hmacSha256Hex(canonical.toString(), CHANNEL_SECRETS.get(channel));
    }

    private static String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record Payment(String payNo, long amountFen, int payMethod) {
    }

    /** 创建支付单（金额取订单实际 payFen），不触发回调。 */
    public Payment createPayment(Buyer buyer, String orderNo, int payMethod) {
        return createPayment(buyer, orderNo, payMethod, orderPayFen(buyer, orderNo));
    }

    public Payment createPayment(Buyer buyer, String orderNo, int payMethod, long amountFen) {
        ObjectNode body = ApiClient.obj(
                "orderNo", orderNo,
                "payMethod", payMethod,
                "amountFen", amountFen,
                "subject", "E2E订单-" + orderNo,
                "terminal", 1);
        ApiClient.Raw raw = buyer.api().mustPost(PAY + "/pays", body, "创建支付单 payMethod=" + payMethod);
        return new Payment(raw.data().path("payNo").asText(), amountFen, payMethod);
    }

    /** 组合支付下单（parts 各手段金额合计=amountFen）。 */
    public Payment createMixedPayment(Buyer buyer, String orderNo, long amountFen, int[] methods, long[] amounts) {
        ObjectNode body = ApiClient.obj(
                "orderNo", orderNo,
                "payMethod", methods[0],
                "amountFen", amountFen,
                "subject", "E2E混合支付-" + orderNo,
                "terminal", 1);
        ArrayNode parts = body.arrayNode();
        for (int i = 0; i < methods.length; i++) {
            parts.add(ApiClient.obj("payMethod", methods[i], "amountFen", amounts[i]));
        }
        body.set("parts", parts);
        ApiClient.Raw raw = buyer.api().mustPost(PAY + "/pays", body, "组合支付下单");
        return new Payment(raw.data().path("payNo").asText(), amountFen, methods[0]);
    }

    /**
     * 发送渠道成功回调（带正确签名）。回调不是白名单路径，携带用户 JWT 通过网关鉴权，
     * 业务验签仍由 pay 服务完成。
     */
    public ApiClient.Raw sendSuccessNotify(Buyer buyer, Payment payment, String notifyId) {
        String channel = CHANNELS.get(payment.payMethod());
        String txn = DataFactory.channelTxnNo();
        String sign = signNotify(channel, notifyId, payment.payNo(), txn, payment.amountFen(), "SUCCESS");
        ObjectNode body = ApiClient.obj(
                "notifyId", notifyId,
                "payNo", payment.payNo(),
                "channelTxnNo", txn,
                "amountFen", payment.amountFen(),
                "status", "SUCCESS",
                "sign", sign);
        return buyer.api().post(PAY + "/notify/pay/" + channel, body);
    }

    /** 发送签名错误的回调（篡改 sign 末位）。 */
    public ApiClient.Raw sendBadSignatureNotify(Buyer buyer, Payment payment) {
        String channel = CHANNELS.get(payment.payMethod());
        String notifyId = DataFactory.notifyId();
        String txn = DataFactory.channelTxnNo();
        String good = signNotify(channel, notifyId, payment.payNo(), txn, payment.amountFen(), "SUCCESS");
        String bad = good.endsWith("0") ? good.substring(0, good.length() - 1) + "1"
                : good.substring(0, good.length() - 1) + "0";
        ObjectNode body = ApiClient.obj(
                "notifyId", notifyId,
                "payNo", payment.payNo(),
                "channelTxnNo", txn,
                "amountFen", payment.amountFen(),
                "status", "SUCCESS",
                "sign", bad);
        return buyer.api().post(PAY + "/notify/pay/" + channel, body);
    }

    /** 下单 → 创建支付单 → mock 回调成功 → 轮询订单待发货。返回支付单（含 payNo/金额）。 */
    public Payment payOrderToWaitShip(Buyer buyer, String orderNo, int payMethod) {
        Payment payment = createPayment(buyer, orderNo, payMethod);
        ApiClient.Raw notify = sendSuccessNotify(buyer, payment, DataFactory.notifyId());
        if (notify.bizCode() != 0) {
            throw new AssertionError("支付回调失败: " + notify.text);
        }
        waitOrderStatus(buyer, orderNo, OS_WAIT_SHIP);
        return payment;
    }

    public JsonNode paymentView(Buyer buyer, String payNo) {
        return buyer.api().get(PAY + "/pays/" + payNo).data();
    }

    public JsonNode refundView(Buyer buyer, String refundNo) {
        return buyer.api().get(PAY + "/refunds/" + refundNo).data();
    }

    // ==================================================================
    // 售后
    // ==================================================================

    public JsonNode aftersaleDetail(Buyer buyer, String aftersaleNo) {
        return buyer.api().get(AFTERSALE + "/aftersales/" + aftersaleNo).data();
    }

    public int aftersaleStatus(Buyer buyer, String aftersaleNo) {
        return aftersaleDetail(buyer, aftersaleNo).path("aftersale").path("status").asInt(-1);
    }

    public void waitAftersaleStatus(Buyer buyer, String no, int status) {
        Poller.def().await("售后单 " + no + " 状态=" + status,
                () -> aftersaleStatus(buyer, no) == status);
    }

    private ObjectNode aftersaleApplyBody(String orderNo, int type, long orderItemId, int qty,
                                          Integer responsibilitySide, Long exchangeSkuId) {
        ObjectNode body = ApiClient.obj(
                "orderNo", orderNo,
                "type", type,
                "reason", "E2E自动化售后 type=" + type,
                "returnFreightFen", 0L);
        if (responsibilitySide != null) {
            body.put("responsibilitySide", responsibilitySide);
        }
        if (exchangeSkuId != null) {
            body.put("exchangeSkuId", exchangeSkuId);
        }
        ArrayNode items = body.arrayNode();
        items.add(ApiClient.obj("orderItemId", orderItemId, "qty", qty));
        body.set("items", items);
        return body;
    }

    public String applyAftersale(Buyer buyer, String orderNo, int type, long orderItemId, int qty) {
        return applyAftersale(buyer, orderNo, type, orderItemId, qty, null, null);
    }

    public String applyAftersale(Buyer buyer, String orderNo, int type, long orderItemId, int qty,
                                 Integer responsibilitySide, Long exchangeSkuId) {
        ObjectNode body = aftersaleApplyBody(orderNo, type, orderItemId, qty, responsibilitySide, exchangeSkuId);
        ApiClient.Raw raw = buyer.api().post(AFTERSALE + "/aftersales", body);
        if (raw.bizCode() != 0) {
            throw new AssertionError("申请售后失败 type=" + type + ": " + raw.text);
        }
        return raw.data().asText();
    }

    /** 申请售后但允许业务失败（用于按环境能力跳过的场景）。 */
    public ApiClient.Raw tryCreateAftersale(Buyer buyer, String orderNo, int type, long orderItemId, int qty,
                                            Integer responsibilitySide, Long exchangeSkuId) {
        ObjectNode body = aftersaleApplyBody(orderNo, type, orderItemId, qty, responsibilitySide, exchangeSkuId);
        return buyer.api().post(AFTERSALE + "/aftersales", body);
    }

    public void merchantAudit(String aftersaleNo, boolean agree) {
        ObjectNode body = ApiClient.obj("agree", agree);
        if (!agree) {
            body.put("rejectReason", "E2E拒绝");
        }
        merchant().mustPost(AFTERSALE + "/merchant/aftersales/" + aftersaleNo + "/audit",
                body, "商家审核");
    }

    public void buyerReturnLogistics(Buyer buyer, String aftersaleNo) {
        buyer.api().mustPost(AFTERSALE + "/aftersales/" + aftersaleNo + "/return-logistics",
                ApiClient.obj("company", "E2E中通", "logisticsNo", DataFactory.logisticsNo()),
                "买家填写退货物流");
    }

    public void merchantReceive(String aftersaleNo, boolean accept) {
        merchant().mustPost(AFTERSALE + "/merchant/aftersales/" + aftersaleNo + "/receive",
                ApiClient.obj("accept", accept), "商家确认收货");
    }

    public void merchantShipExchange(String aftersaleNo, Long exchangeSkuId) {
        ObjectNode body = ApiClient.obj("company", "E2E圆通",
                "logisticsNo", DataFactory.logisticsNo());
        if (exchangeSkuId != null) {
            body.put("exchangeSkuId", exchangeSkuId);
        }
        merchant().mustPost(AFTERSALE + "/merchant/aftersales/" + aftersaleNo + "/ship",
                body, "商家换货/补发发货");
    }

    public void buyerExchangeConfirm(Buyer buyer, String aftersaleNo) {
        buyer.api().mustPost(AFTERSALE + "/aftersales/" + aftersaleNo + "/exchange-confirm",
                null, "买家确认签收换货/补发");
    }

    // ==================================================================
    // 清算 / 结算 / 提现
    // ==================================================================

    public JsonNode merchantAccount() {
        return merchant().get(SETTLEMENT + "/merchant/account").data();
    }

    public JsonNode clearingByOrder(String orderNo) {
        return Poller.longTimeout().awaitValue("清算流水登记 orderNo=" + orderNo, () -> {
            JsonNode list = merchant().get(SETTLEMENT + "/merchant/clearing?pageNum=1&pageSize=100")
                    .data().path("list");
            for (JsonNode row : list) {
                if (orderNo.equals(row.path("orderNo").asText())) {
                    return row;
                }
            }
            return null;
        });
    }

    public void setMerchantLevel(int level) {
        admin().mustPut(SETTLEMENT + "/admin/merchants/" + merchantId() + "/level",
                ApiClient.obj("level", level), "调整商户等级 " + level);
    }

    public JsonNode manualSettle(LocalDate date) {
        return admin().post(SETTLEMENT + "/admin/reconcile/settle?date=" + date, null).data();
    }

    public JsonNode statements() {
        return merchant().get(SETTLEMENT + "/merchant/statements?pageNum=1&pageSize=100").data();
    }

    public JsonNode withdrawals() {
        return merchant().get(SETTLEMENT + "/merchant/withdrawals?pageNum=1&pageSize=100").data();
    }

    public ApiClient.Raw applyWithdrawal(long amountFen) {
        ObjectNode body = ApiClient.obj(
                "amountFen", amountFen,
                "channel", 1,
                "channelAccount", "622202" + System.currentTimeMillis() % 1000000000L,
                "accountName", "E2E商户",
                "bankName", "E2E银行");
        return merchant().post(SETTLEMENT + "/merchant/withdrawals", body);
    }

    /**
     * 保证金三段式第一段：发起缴费（B10：payScene=4 真实支付单）。
     * 返回 DP 流水号 + payNo；余额在渠道回调 → ORDER_PAID 扇出 CAS 10→20 后才增加。
     */
    public DepositPay startDeposit(long amountFen) {
        ObjectNode body = ApiClient.obj(
                "amountFen", amountFen,
                "payMethod", PAY_WECHAT,
                "terminal", 1,
                "clientToken", DataFactory.clientToken());
        ApiClient.Raw raw = merchant().mustPost(SETTLEMENT + "/merchant/deposit", body, "发起保证金缴纳");
        JsonNode d = raw.data();
        return new DepositPay(d.path("logNo").asText(), d.path("payNo").asText(), amountFen);
    }

    /** 保证金三段式第二段：用商户 JWT 发送 mock 渠道成功回调（回调路径非白名单，需登录态过网关）。 */
    public ApiClient.Raw sendDepositSuccessNotify(DepositPay deposit, String notifyId) {
        String channel = CHANNELS.get(PAY_WECHAT);
        String txn = DataFactory.channelTxnNo();
        String sign = signNotify(channel, notifyId, deposit.payNo(), txn, deposit.amountFen(), "SUCCESS");
        ObjectNode body = ApiClient.obj(
                "notifyId", notifyId,
                "payNo", deposit.payNo(),
                "channelTxnNo", txn,
                "amountFen", deposit.amountFen(),
                "status", "SUCCESS",
                "sign", sign);
        return merchant().post(PAY + "/notify/pay/" + channel, body);
    }

    /** 完整缴纳：发起 → 回调 → 返回第一段原始响应（流水号在 data.logNo）。调用方需自行轮询余额入账。 */
    public DepositPay payDeposit(long amountFen) {
        DepositPay deposit = startDeposit(amountFen);
        ApiClient.Raw notify = sendDepositSuccessNotify(deposit, DataFactory.notifyId());
        if (notify.bizCode() != 0) {
            throw new AssertionError("保证金支付回调失败: " + notify.text);
        }
        return deposit;
    }

    public record DepositPay(String logNo, String payNo, long amountFen) {
    }

    /** 造一笔已支付订单（买家视角全链路到待发货）。 */
    public Purchased purchaseToWaitShip(long skuPrice, int qty, int payMethod) {
        ensureMerchantOnboarded();
        Buyer buyer = newBuyer();
        Sku sku = createOnSaleSku(skuPrice, Math.max(qty + 5, 10));
        ObjectNode body = baseOrderBody(buyer, sku, qty);
        String orderNo = createOrder(buyer, body);
        Payment payment = payOrderToWaitShip(buyer, orderNo, payMethod);
        return new Purchased(buyer, sku, orderNo, payment);
    }

    /** 造一笔已确认收货（已完成）订单。 */
    public Purchased purchaseCompleted(long skuPrice, int qty, int payMethod) {
        Purchased p = purchaseToWaitShip(skuPrice, qty, payMethod);
        shipOrder(p.orderNo());
        waitOrderStatus(p.buyer(), p.orderNo(), OS_WAIT_RECEIVE);
        p.buyer().api().mustPost(ORDER + "/orders/" + p.orderNo() + "/confirm", null, "确认收货");
        waitOrderStatus(p.buyer(), p.orderNo(), OS_COMPLETED);
        return p;
    }

    public record Purchased(Buyer buyer, Sku sku, String orderNo, Payment payment) {
        public long firstItemId() {
            JsonNode mine = buyer.api().get(ORDER + "/orders/" + orderNo).data();
            return mine.path("items").get(0).path("orderItemId").asLong();
        }
    }
}
