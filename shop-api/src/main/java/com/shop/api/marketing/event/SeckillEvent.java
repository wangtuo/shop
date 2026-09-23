package com.shop.api.marketing.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 秒杀事件（Topic：SECKILL_EVENT，CONTRACTS.md §5）。
 *
 * <p>由营销域在下单锁定/支付扣减/取消释放时发出，驱动秒杀库存状态推进与对账；
 * {@code type} 取值见 {@code SeckillOpType}（1 锁定 2 扣减 3 释放）。
 * 消费端以 eventId/orderNo 幂等。秒杀与所有其他优惠互斥（design.md 4.2.3）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SeckillEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 秒杀活动 ID */
    private Long activityId;

    /** SKU ID */
    private Long skuId;

    /** 用户 ID */
    private Long userId;

    /** 关联订单号（幂等键） */
    private String orderNo;

    /** 操作类型：1 锁定 2 扣减 3 释放（SeckillOpType） */
    private Integer type;

    /** 数量 */
    private Long qty;
}
