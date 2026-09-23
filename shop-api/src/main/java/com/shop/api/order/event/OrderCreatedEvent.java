package com.shop.api.order.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单创建事件（Topic ORDER_CREATED，CONTRACTS.md §5）。
 *
 * <p>生产者：order（下单落库为待付款后）；消费者：product（TCC-try 锁库存）、
 * marketing（券预核销/秒杀确认）。下单链路任一步失败按逆序补偿。
 *
 * <p>{@code status} 固定为待付款 10；{@code expirePaySeconds} 按 design.md 5.3.3：
 * 普通 1800s、秒杀 900s、拼团开团 24h（成团后再给 30 分钟）、预售尾款 3 天。
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderCreatedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 订单号（18 位） */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 订单类型：1 普通 2 秒杀 3 拼团 4 预售 5 换货 */
    private Integer orderType;

    /** 订单状态：创建后固定为 10 待付款 */
    private Integer status;

    /** 使用积分抵扣金额（分，无积分抵扣为 0） */
    private Long usedPointsFen;

    /** 使用的用户优惠券 ID（未用券为 null） */
    private Long userCouponId;

    /** 运费（分） */
    private Long freightFen;

    /** 支付超时秒数（普通 30 分钟 / 秒杀 15 分钟 / 拼团 24h+30 分钟 / 预售尾款 3 天） */
    private Long expirePaySeconds;

    /** 订单明细 */
    @lombok.Builder.Default
    private List<OrderItemMessage> items = new ArrayList<>();

    /** 父订单号（预售尾款单指向定金单 orderNo；null=普通单/定金单） */
    private String parentOrderNo;
}
