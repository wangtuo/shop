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
 * 订单取消事件（Topic ORDER_CANCELLED，CONTRACTS.md §5）。
 *
 * <p>生产者：order（用户取消 / 支付超时 / 商家取消）；消费者：product（释放锁定库存）、
 * marketing（释放预核销券、秒杀占位）、user（释放冻结积分）。
 *
 * <p>{@code cancelType}：1 用户 2 超时 3 商家（见 {@code CancelTypes}）。
 * 超时规则见 design.md 5.3.3：普通 30 分钟、秒杀 15 分钟、拼团 24h+30 分钟、预售尾款 3 天。
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderCancelledEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 订单号（18 位） */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 取消类型：1 用户 2 超时 3 商家 */
    private Integer cancelType;

    /** 订单类型：1 普通 2 秒杀 3 拼团 4 预售 5 换货；null 视为普通单（历史消息兼容，TRADE C31） */
    private Integer orderType;

    /** 已使用积分抵扣金额（分，退回冻结积分） */
    private Long usedPointsFen;

    /** 已预核销的用户优惠券 ID（未用券为 null） */
    private Long userCouponId;

    /** 秒杀活动 ID（秒杀订单，用于释放秒杀占位库存） */
    private Long seckillActivityId;

    /** 订单明细 */
    @lombok.Builder.Default
    private List<OrderItemMessage> items = new ArrayList<>();
}
