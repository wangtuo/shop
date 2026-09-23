package com.shop.order.support;

import com.shop.api.order.enums.ItemAftersaleStatuses;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 售后事件 → 明细/订单状态映射单测：覆盖全部售后类型 × 关键售后状态边界。
 */
class AftersaleStatusMappingTest {

    private final AftersaleStatusMapping mapping = new AftersaleStatusMapping();

    @Test
    void itemStatus_refundOnlyFlow() {
        // 仅退款：申请 10 → APPLYING；退款中 40 → REFUNDING；完成 50 → REFUNDED
        assertThat(mapping.mapItemStatus(1, 10)).isEqualTo(ItemAftersaleStatuses.APPLYING);
        assertThat(mapping.mapItemStatus(1, 40)).isEqualTo(ItemAftersaleStatuses.REFUNDING);
        assertThat(mapping.mapItemStatus(1, 50)).isEqualTo(ItemAftersaleStatuses.REFUNDED);
        // 平台介入保持申请中
        assertThat(mapping.mapItemStatus(1, 80)).isEqualTo(ItemAftersaleStatuses.APPLYING);
    }

    @Test
    void itemStatus_returnRefundFlow() {
        // 退货退款：20/30 → RETURNING（仅退款类型在 20/30 仍为 APPLYING）
        assertThat(mapping.mapItemStatus(2, 20)).isEqualTo(ItemAftersaleStatuses.RETURNING);
        assertThat(mapping.mapItemStatus(2, 30)).isEqualTo(ItemAftersaleStatuses.RETURNING);
        assertThat(mapping.mapItemStatus(1, 20)).isEqualTo(ItemAftersaleStatuses.APPLYING);
        assertThat(mapping.mapItemStatus(2, 50)).isEqualTo(ItemAftersaleStatuses.REFUNDED);
    }

    @Test
    void itemStatus_exchangeFlow() {
        assertThat(mapping.mapItemStatus(3, 41)).isEqualTo(ItemAftersaleStatuses.EXCHANGING);
        assertThat(mapping.mapItemStatus(3, 42)).isEqualTo(ItemAftersaleStatuses.EXCHANGING);
        assertThat(mapping.mapItemStatus(3, 43)).isEqualTo(ItemAftersaleStatuses.EXCHANGING);
        // 换货/补发货完成 → FINISHED（非退款态）
        assertThat(mapping.mapItemStatus(3, 50)).isEqualTo(ItemAftersaleStatuses.FINISHED);
        assertThat(mapping.mapItemStatus(4, 50)).isEqualTo(ItemAftersaleStatuses.FINISHED);
    }

    @Test
    void itemStatus_rejectedOrRevoked_backToNone() {
        for (int type : new int[]{1, 2, 3, 4, 5}) {
            assertThat(mapping.mapItemStatus(type, 55)).isEqualTo(ItemAftersaleStatuses.NONE);
            assertThat(mapping.mapItemStatus(type, 90)).isEqualTo(ItemAftersaleStatuses.NONE);
        }
    }

    @Test
    void orderStatus_enterAftersaleByType() {
        // 10 待审：仅退款 60 / 退货退款 61 / 换货 62 / 补发货 62 / 价保 60
        assertThat(mapping.mapOrderStatus(1, 10)).isEqualTo(60);
        assertThat(mapping.mapOrderStatus(2, 10)).isEqualTo(61);
        assertThat(mapping.mapOrderStatus(3, 10)).isEqualTo(62);
        assertThat(mapping.mapOrderStatus(4, 10)).isEqualTo(62);
        assertThat(mapping.mapOrderStatus(5, 10)).isEqualTo(60);
        // 换货发货中 → 62
        assertThat(mapping.mapOrderStatus(3, 42)).isEqualTo(62);
    }

    @Test
    void orderStatus_finished_refundKeepsOrderAwaitingRefundEvent() {
        // 退款类完成：等 REFUND_SUCCESS 决定关单，整单状态暂不动
        assertThat(mapping.mapOrderStatus(1, 50)).isEqualTo(AftersaleStatusMapping.ORDER_KEEP);
        assertThat(mapping.mapOrderStatus(2, 50)).isEqualTo(AftersaleStatusMapping.ORDER_KEEP);
        assertThat(mapping.mapOrderStatus(5, 50)).isEqualTo(AftersaleStatusMapping.ORDER_KEEP);
        // 换货/补发货完成：恢复履约前状态
        assertThat(mapping.mapOrderStatus(3, 50)).isEqualTo(AftersaleStatusMapping.ORDER_RESUME);
        assertThat(mapping.mapOrderStatus(4, 50)).isEqualTo(AftersaleStatusMapping.ORDER_RESUME);
        // 拒绝/撤销：恢复
        assertThat(mapping.mapOrderStatus(1, 55)).isEqualTo(AftersaleStatusMapping.ORDER_RESUME);
        assertThat(mapping.mapOrderStatus(2, 90)).isEqualTo(AftersaleStatusMapping.ORDER_RESUME);
    }

    @Test
    void active_onlyInProgressStatuses() {
        assertThat(mapping.active(ItemAftersaleStatuses.APPLYING)).isTrue();
        assertThat(mapping.active(ItemAftersaleStatuses.REFUNDING)).isTrue();
        assertThat(mapping.active(ItemAftersaleStatuses.RETURNING)).isTrue();
        assertThat(mapping.active(ItemAftersaleStatuses.EXCHANGING)).isTrue();
        assertThat(mapping.active(ItemAftersaleStatuses.NONE)).isFalse();
        assertThat(mapping.active(ItemAftersaleStatuses.REFUNDED)).isFalse();
        assertThat(mapping.active(ItemAftersaleStatuses.FINISHED)).isFalse();
    }
}
