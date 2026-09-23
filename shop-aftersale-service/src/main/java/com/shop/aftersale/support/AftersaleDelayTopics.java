package com.shop.aftersale.support;

import com.shop.common.constant.MqTopics;

/**
 * 售后域延时消息 Topic / 超时种类（双保险：MQ 延时 + DB 扫描）。
 */
public final class AftersaleDelayTopics {

    private AftersaleDelayTopics() {
    }

    /** 售后超时统一延时 Topic，tag 取 KIND_* */
    public static final String AFTERSALE_TIMEOUT = MqTopics.AFTERSALE_TIMEOUT;

    /** 商家 2 天未审核 */
    public static final String KIND_AUDIT = "audit";
    /** 商家 3 天未确认收货 */
    public static final String KIND_RECEIVE = "receive";
    /** 换货 5 天未发货 */
    public static final String KIND_EXCHANGE_SHIP = "exchange_ship";
    /** 运费险 72h 理赔 */
    public static final String KIND_INSURANCE = "insurance";
    /** 平台介入 3 天举证截止 */
    public static final String KIND_EVIDENCE = "evidence";
}
