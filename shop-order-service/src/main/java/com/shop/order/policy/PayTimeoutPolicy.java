package com.shop.order.policy;

import com.shop.api.order.enums.OrderTypes;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.constant.MqTopics;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 支付超时策略（design.md 5.3.3 超时矩阵）。
 *
 * <ul>
 *     <li>普通订单：30 分钟（{@link MqTopics#DELAY_30_MIN_SECONDS}）</li>
 *     <li>秒杀订单：15 分钟（{@link MqTopics#DELAY_15_MIN_SECONDS}）</li>
 *     <li>拼团订单：开团后 24 小时内支付，成团后再给 30 分钟（开单按 24h 投递）</li>
 *     <li>预售订单：尾款期 3 天</li>
 * </ul>
 */
@Component
public class PayTimeoutPolicy {

    /** 3 天（预售尾款支付期） */
    public static final long DELAY_3_DAY_SECONDS = 3 * MqTopics.DELAY_24_HOUR_SECONDS;

    /**
     * 拼团成团后的追加支付窗口（B1）：事件到达时刻 +30min，CAS 只宽不窄，
     * 不改动开单时的 24h 分支。
     */
    public long groupSuccessExtendSeconds() {
        return MqTopics.DELAY_30_MIN_SECONDS;
    }

    public long expirePaySeconds(Integer orderType) {
        if (orderType == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单类型不能为空");
        }
        return switch (orderType) {
            case OrderTypes.NORMAL, OrderTypes.EXCHANGE -> MqTopics.DELAY_30_MIN_SECONDS;
            case OrderTypes.SECKILL -> MqTopics.DELAY_15_MIN_SECONDS;
            case OrderTypes.GROUPBUY -> MqTopics.DELAY_24_HOUR_SECONDS;
            case OrderTypes.PRESALE -> DELAY_3_DAY_SECONDS;
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "未知订单类型: " + orderType);
        };
    }

    /** 支付超时截止时间（DB 扫描双保险使用）。 */
    public LocalDateTime expireTime(Integer orderType, LocalDateTime now) {
        return now.plusSeconds(expirePaySeconds(orderType));
    }
}
