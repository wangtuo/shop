package com.shop.order.policy;

import com.shop.common.constant.MqTopics;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 订单履约时间窗策略（design.md 5.3.3 / 5.2）。
 * <ul>
 *     <li>发货后 10 天自动确认收货（{@link MqTopics#DELAY_10_DAY_SECONDS}）；</li>
 *     <li>确认收货后 15 天售后期结束（{@link MqTopics#DELAY_15_DAY_SECONDS}），
 *     售后期满订单关闭并发出 ORDER_COMPLETED。</li>
 * </ul>
 */
@Component
public class OrderTimePolicy {

    /** 发货 → 自动确认收货：10 天 */
    public LocalDateTime autoConfirmDeadline(LocalDateTime shipTime) {
        return shipTime.plusSeconds(MqTopics.DELAY_10_DAY_SECONDS);
    }

    /** 确认收货 → 售后期结束：15 天 */
    public LocalDateTime aftersaleDeadline(LocalDateTime confirmTime) {
        return confirmTime.plusSeconds(MqTopics.DELAY_15_DAY_SECONDS);
    }
}
