package com.shop.order.support;

import com.shop.api.order.enums.ItemAftersaleStatuses;
import org.springframework.stereotype.Component;

/**
 * 售后事件（AFTERSALE_CHANGED）→ 订单/明细状态映射（CONTRACTS §4 售后码值）。
 *
 * <p>纯规则组件，便于单测覆盖全部售后状态边界。售后域状态：
 * <pre>
 * 10 待商家审核 / 20 待买家退货 / 30 商家收货中 / 40 退款中 /
 * 41 待换货发货 / 42 换货已发货 / 43 换货待收货 /
 * 50 已完成 / 55 已拒绝 / 80 平台介入中 / 90 已撤销
 * </pre>
 */
@Component
public class AftersaleStatusMapping {

    /** 订单状态目标：恢复进入售后前状态 */
    public static final int ORDER_RESUME = -1;
    /** 订单状态目标：不变化（如退款完成以 REFUND_SUCCESS 为准） */
    public static final int ORDER_KEEP = Integer.MIN_VALUE;

    /**
     * 明细售后状态映射。
     *
     * @param type          售后类型 1 仅退款 2 退货退款 3 换货 4 补发货 5 价保
     * @param aftersaleStatus 售后单新状态
     * @return {@link ItemAftersaleStatuses}
     */
    public int mapItemStatus(int type, int aftersaleStatus) {
        return switch (aftersaleStatus) {
            // 拒绝/撤销：明细回到无售后，订单恢复履约
            case 55, 90 -> ItemAftersaleStatuses.NONE;
            // 平台介入：保持申请中
            case 80 -> ItemAftersaleStatuses.APPLYING;
            case 10 -> ItemAftersaleStatuses.APPLYING;
            case 40 -> ItemAftersaleStatuses.REFUNDING;
            case 20, 30 -> type == 2
                    ? ItemAftersaleStatuses.RETURNING
                    : ItemAftersaleStatuses.APPLYING;
            case 41, 42, 43 -> ItemAftersaleStatuses.EXCHANGING;
            case 50 -> (type == 3 || type == 4)
                    ? ItemAftersaleStatuses.FINISHED
                    : ItemAftersaleStatuses.REFUNDED;
            default -> ItemAftersaleStatuses.APPLYING;
        };
    }

    /**
     * 订单整单状态目标。
     *
     * @return 60/61/62 进入对应售后态；{@link #ORDER_RESUME} 恢复售后前状态；
     *         {@link #ORDER_KEEP} 维持现状（等待 REFUND_SUCCESS 决定关闭）
     */
    public int mapOrderStatus(int type, int aftersaleStatus) {
        return switch (aftersaleStatus) {
            // 终结但带退款：是否全额关单由 REFUND_SUCCESS(refundType) 决定
            case 50 -> (type == 3 || type == 4) ? ORDER_RESUME : ORDER_KEEP;
            case 55, 90 -> ORDER_RESUME;
            case 41, 42, 43 -> 62;
            default -> switch (type) {
                case 2 -> 61;
                case 3, 4 -> 62;
                default -> 60;
            };
        };
    }

    /** 明细售后状态是否仍在进行中（用于售后期满关闭判断）。 */
    public boolean active(int itemAftersaleStatus) {
        return itemAftersaleStatus == ItemAftersaleStatuses.APPLYING
                || itemAftersaleStatus == ItemAftersaleStatuses.REFUNDING
                || itemAftersaleStatus == ItemAftersaleStatuses.RETURNING
                || itemAftersaleStatus == ItemAftersaleStatuses.EXCHANGING;
    }
}
