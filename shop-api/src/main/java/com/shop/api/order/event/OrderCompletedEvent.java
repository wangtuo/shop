package com.shop.api.order.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 订单完成事件（Topic ORDER_COMPLETED，CONTRACTS.md §5）。
 *
 * <p>生产者：order（订单完结：完成后售后期满或售后全部终结，状态 40 已完成/70 已关闭）；
 * 消费者：settlement（结算完成转可提现，B 级商户按结算周期处理，见 design.md 7.3.2）。
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderCompletedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 订单号（18 位） */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 商户 ID */
    private Long merchantId;
}
