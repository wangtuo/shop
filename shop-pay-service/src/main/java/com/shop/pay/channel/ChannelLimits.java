package com.shop.pay.channel;

import com.shop.api.pay.enums.PayMethods;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 各支付渠道限额与终端支持（design 6.1），金额单位：分。
 */
public final class ChannelLimits {

    /** 微信/支付宝/云闪付单笔限额 5 万元（5_000_000 分） */
    public static final long LIMIT_5W = 5_000_000L;
    /** 银行卡快捷支付按银行限额，mock 统一 2 万元 */
    public static final long LIMIT_BANK = 2_000_000L;
    /** 白条/花呗按授信额度，mock 统一 5 万元 */
    public static final long LIMIT_CREDIT = 5_000_000L;

    private static final Map<PayMethods, Long> SINGLE_LIMIT = new EnumMap<>(PayMethods.class);
    private static final Map<PayMethods, Set<Integer>> TERMINALS = new EnumMap<>(PayMethods.class);

    static {
        SINGLE_LIMIT.put(PayMethods.WECHAT, LIMIT_5W);
        SINGLE_LIMIT.put(PayMethods.ALIPAY, LIMIT_5W);
        SINGLE_LIMIT.put(PayMethods.UNIONPAY, LIMIT_5W);
        SINGLE_LIMIT.put(PayMethods.BANK_CARD, LIMIT_BANK);
        SINGLE_LIMIT.put(PayMethods.HUABEI, LIMIT_CREDIT);
        SINGLE_LIMIT.put(PayMethods.BAITIAO, LIMIT_CREDIT);
        // 余额支付受账户余额限制，无固定单笔限额（由用户域余额校验）

        Set<Integer> all = Set.of(1, 2, 3, 4);
        Set<Integer> appH5 = Set.of(1, 2);
        Set<Integer> appOnly = Set.of(1);
        TERMINALS.put(PayMethods.WECHAT, all);
        TERMINALS.put(PayMethods.ALIPAY, all);
        TERMINALS.put(PayMethods.BALANCE, all);
        TERMINALS.put(PayMethods.BANK_CARD, appH5);
        TERMINALS.put(PayMethods.UNIONPAY, appH5);
        TERMINALS.put(PayMethods.HUABEI, appOnly);
        TERMINALS.put(PayMethods.BAITIAO, appOnly);
    }

    private ChannelLimits() {
    }

    /** 支付方式 → mock 渠道编码；余额支付无第三方渠道。 */
    public static String channelCode(PayMethods method) {
        return switch (method) {
            case WECHAT -> "MOCK_WECHAT";
            case ALIPAY -> "MOCK_ALIPAY";
            case BANK_CARD -> "MOCK_BANK";
            case UNIONPAY -> "MOCK_UQR";
            case HUABEI -> "MOCK_HUABEI";
            case BAITIAO -> "MOCK_BAITIAO";
            case BALANCE -> "BALANCE";
        };
    }

    public static boolean isBalance(String channelCode) {
        return "BALANCE".equals(channelCode);
    }

    /**
     * 限额 + 终端校验。
     *
     * @throws IllegalArgumentException 终端不支持
     * @throws com.shop.common.exception.BizException 超限额（PAY_ERROR）
     */
    public static void check(PayMethods method, long amountFen, Integer terminal) {
        Set<Integer> supported = TERMINALS.get(method);
        if (terminal != null && !supported.contains(terminal)) {
            throw new IllegalArgumentException(
                    method.getDesc() + "不支持该终端: " + terminal + "，支持终端码 " + supported);
        }
        Long limit = SINGLE_LIMIT.get(method);
        if (limit != null && amountFen > limit) {
            throw new com.shop.common.exception.BizException(
                    com.shop.common.exception.ErrorCode.PAY_ERROR,
                    method.getDesc() + "单笔金额超出限额 " + amountFen + " > " + limit + " 分");
        }
    }
}
