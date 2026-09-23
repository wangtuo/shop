package com.shop.order.support;

import com.shop.common.constant.MqTopics;

/**
 * 订单域内部延时消息 Topic（支付超时关单 / 自动收货 / 售后期满）。
 *
 * <p>这些 Topic 仅订单域自产自消，常量已收口到 shop-common {@link MqTopics}，
 * 此处保留别名以减少本域引用改动；禁止散落硬编码。延时秒数引用 MqTopics.DELAY_*。
 */
public final class OrderDelayTopics {

    /** 支付超时延时消息（订单仍为待付款则取消） */
    public static final String ORDER_PAY_TIMEOUT = MqTopics.ORDER_PAY_TIMEOUT;
    /** 自动确认收货延时消息 */
    public static final String ORDER_AUTO_CONFIRM = MqTopics.ORDER_AUTO_CONFIRM;
    /** 售后期结束延时消息 */
    public static final String ORDER_AFTERSALE_WINDOW = MqTopics.ORDER_AFTERSALE_WINDOW;

    private OrderDelayTopics() {
    }
}
