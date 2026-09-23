package com.shop.api.marketing.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 预售事件（Topic：PRESALE_EVENT，CONTRACTS.md §5）。
 *
 * <p>驱动预售单状态推进与尾款超时：1 定金支付 / 2 尾款提醒（进入尾款期前）/
 * 3 取消（尾款期通常 3 天内未付，订单自动取消、定金不退）。
 * 消费端以 eventId/orderNo 幂等。优惠券仅尾款阶段可用，定金阶段不可用（design.md 4.2.3、4.5）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PresaleEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 预售活动 ID */
    private Long activityId;

    /** 用户 ID */
    private Long userId;

    /** 关联订单号（幂等键） */
    private String orderNo;

    /** 操作类型：1 定金支付 2 尾款提醒 3 取消（PresaleOpType） */
    private Integer type;

    /** 尾款支付截止时间（毫秒时间戳），超时未付自动取消、定金不退 */
    private Long finalPayDeadline;
}
