package com.shop.e2e.support;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 唯一数据工厂：时间戳 + 进程内序列/随机数，保证测试可重复执行而不与历史数据冲突。
 */
public final class DataFactory {

    private static final AtomicLong SEQ = new AtomicLong(0);

    private DataFactory() {
    }

    /** 进程内唯一短序号（0 起）。 */
    public static long seq() {
        return SEQ.incrementAndGet();
    }

    /** 唯一用户名：e2e_ + yyMMddHHmmss + 4 位序列，长度 ≤ 32。 */
    public static synchronized String uniqueUsername(String prefix) {
        long ts = System.currentTimeMillis() / 1000 % 10_000_000_000L;
        String name = prefix + "_" + Long.toString(ts, 36) + String.format("%03d", seq() % 1000);
        return name.length() > 32 ? name.substring(0, 32) : name;
    }

    /**
     * 唯一 11 位手机号：1 开头，后 10 位取毫秒时间戳与序列拼装（通过 ^1\d{10}$ 校验）。
     */
    public static synchronized String uniquePhone() {
        long tail = (System.currentTimeMillis() % 10_000_000_000L) * 10L + (seq() % 10);
        tail = tail % 10_000_000_000L;
        return "1" + String.format("%010d", tail);
    }

    /** 下单幂等 clientToken。 */
    public static String clientToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 支付回调通知 ID。 */
    public static String notifyId() {
        return "NTF" + System.currentTimeMillis() + String.format("%04d", seq() % 10000);
    }

    /** 渠道交易流水号。 */
    public static String channelTxnNo() {
        return "TXN" + System.currentTimeMillis() + seq();
    }

    /** 物流单号。 */
    public static String logisticsNo() {
        return "SF" + System.currentTimeMillis() + String.format("%04d", seq() % 10000);
    }

    /** 唯一 SKU 编码。 */
    public static String skuCode() {
        return "E2E-SKU-" + System.currentTimeMillis() + "-" + seq();
    }

    /** 唯一品牌/类目/商品/券/活动名。 */
    public static String uniqueName(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-" + seq();
    }
}
