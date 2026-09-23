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
 * 订单发货事件（Topic ORDER_SHIPPED，CONTRACTS.md §5）。
 *
 * <p>生产者：order（商家发货）；状态由待发货 20 推进至待收货 30。
 * {@code autoConfirmDeadline} 为自动确认收货截止时间（毫秒时间戳），
 * 按 design.md 5.3.3「发货后 10 天」计算，到期由定时任务自动确认收货。
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderShippedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 订单号（18 位） */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 物流单号 */
    private String logisticsNo;

    /** 自动确认收货截止时间（毫秒时间戳，发货后 10 天） */
    private Long autoConfirmDeadline;

    /** 是否购买运费险：0 否 1 是（发货即建售后窗口，必须在此携带） */
    private Integer hasFreightInsurance;

    /** 运费险保费（分，未购险为 0/null；理赔窗口判定随事件携带避免回查） */
    private Long insurancePremiumFen;

    /** 订单明细（实际发货的 SKU 与数量） */
    @lombok.Builder.Default
    private List<OrderItemMessage> items = new ArrayList<>();
}
