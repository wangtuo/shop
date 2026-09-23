package com.shop.api.aftersale.event;

import com.shop.api.aftersale.dto.AftersaleItemMessage;
import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 售后单状态变更事件（Topic：{@code AFTERSALE_CHANGED}）。
 *
 * <p>事件链路（CONTRACTS.md §5）：售后域在审核、退货物流、收货确认、退款、换货发货、
 * 平台仲裁等每次状态流转时发送；订单域据此更新明细售后状态，商品域在
 * <b>买家责任</b>事件做退货入库，支付 / 清算域据退款结果走 REFUND_SUCCESS 链路。
 *
 * <p>{@code type} 见 {@code com.shop.api.aftersale.enums.AftersaleTypes}，
 * {@code oldStatus}/{@code newStatus} 见 {@code com.shop.api.aftersale.enums.AftersaleStatuses}，
 * {@code responsibilitySide} 见 {@code com.shop.api.aftersale.enums.ResponsibilitySide}。
 * 事件体字段自包含，消费者以 eventId 幂等去重。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class AftersaleChangedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 售后单号（AS + yyyyMMdd + 10 位序列，CONTRACTS.md §6） */
    private String aftersaleNo;

    /** 业务订单号 */
    private String orderNo;

    /** 买家用户 ID */
    private Long userId;

    /** 商户 ID */
    private Long merchantId;

    /** 售后类型：1 仅退款 2 退货退款 3 换货 4 补发货 5 价保 */
    private Integer type;

    /** 变更前状态（首次申请时可为空） */
    private Integer oldStatus;

    /** 变更后状态（见 AftersaleStatuses） */
    private Integer newStatus;

    /** 本次售后退款总金额（分；换货 / 补发货为 0） */
    private Long refundFen;

    /** 责任方：1 商家 2 买家 3 运费险（决定运费承担与库存处理） */
    private Integer responsibilitySide;

    /** 退货 / 换货物流单号（用户寄回或商家换发时填写） */
    private String logisticsNo;

    /** 售后明细行（按订单明细拆分，默认空集合，禁止 null） */
    @Builder.Default
    private List<AftersaleItemMessage> items = new ArrayList<>();
}
