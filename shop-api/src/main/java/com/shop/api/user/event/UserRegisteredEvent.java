package com.shop.api.user.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 用户注册成功事件（Topic：{@code MqTopics.USER_REGISTERED}）。
 *
 * <p>用户域注册事务内由 outbox 生产；营销域新人礼包等消费。
 * 事件体不含手机号/密码等敏感信息，字段自包含，消费者不允许回查用户库。
 *
 * <p>规则来源：GAP_PLAN_USER C41（GAP_PLAN_MASTER §2.1 裁决 2 唯一形态）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class UserRegisteredEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 注册用户 ID */
    private Long userId;

    /** 注册时间（epoch 毫秒） */
    private Long registerTime;

    /** 用户类型（恒 0 普通用户，留作校验） */
    private Integer userType;
}
