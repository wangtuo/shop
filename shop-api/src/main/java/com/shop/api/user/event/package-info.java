/**
 * 用户域 MQ 事件契约包。
 *
 * <p>事件继承 {@code com.shop.common.model.BaseEvent}（自带 eventId 幂等键、occurredAt、bizNo），
 * 事件体字段自包含，消费者凭 eventId/bizNo 幂等消费，不允许回查用户服务数据库。
 *
 * <p>{@code PointsChangedEvent} 对应 Topic {@code POINTS_CHANGED}：积分获取/消耗/冻结/释放/退回/过期清零后发送，
 * 用于域内状态推进与积分对账单据。
 *
 * <p>规则来源：CONTRACTS.md §5 事件契约；design.md 2.2.2 积分规则。
 */
package com.shop.api.user.event;
