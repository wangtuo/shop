package com.shop.api.user.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 积分变动事件（Topic：POINTS_CHANGED）。
 *
 * <p>用户域积分每次获取/消耗/冻结/释放/退回/过期清零后发送，用于域内状态推进与对账单据。
 * 事件体字段自包含，消费者不允许回查用户库。
 *
 * <p>规则来源：CONTRACTS.md §5 事件契约、design.md 2.2.2 积分规则。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class PointsChangedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long userId;

    /** 变动类型，取值见 {@code PointsChangeType}：1 获取 2 消耗 3 冻结 4 释放 5 退回 6 过期清零 */
    private Integer changeType;

    /** 本次变动积分个数（正数，方向由 changeType 表达） */
    private Long points;

    /** 变动后可用积分余额（冻结/释放类事件记录的是可用余额） */
    private Long balanceAfter;

    /** 业务场景，取值见 {@code PointsScene} */
    private Integer scene;

    /**
     * 业务单号（订单号/退款单号/签到流水等），幂等键。
     *
     * <p>显式声明以固定契约字段；与 {@link BaseEvent#getBizNo()} 同一属性，
     * 序列化/反序列化只呈现一个 bizNo。
     */
    private String bizNo;
}
