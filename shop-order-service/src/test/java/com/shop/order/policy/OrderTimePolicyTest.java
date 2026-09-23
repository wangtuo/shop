package com.shop.order.policy;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单履约时间窗单测：发货后 10 天自动确认、确认后 15 天售后期结束。
 */
class OrderTimePolicyTest {

    private final OrderTimePolicy policy = new OrderTimePolicy();

    @Test
    void autoConfirmDeadline_shipTimePlus10Days() {
        LocalDateTime shipTime = LocalDateTime.of(2026, 3, 15, 8, 30, 0);
        assertThat(policy.autoConfirmDeadline(shipTime))
                .isEqualTo(LocalDateTime.of(2026, 3, 25, 8, 30, 0));
    }

    @Test
    void aftersaleDeadline_confirmTimePlus15Days() {
        LocalDateTime confirmTime = LocalDateTime.of(2026, 3, 15, 8, 30, 0);
        assertThat(policy.aftersaleDeadline(confirmTime))
                .isEqualTo(LocalDateTime.of(2026, 3, 30, 8, 30, 0));
    }

    @Test
    void aftersaleWindowIsFiveDaysLongerThanAutoConfirm() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        long autoConfirm = java.time.Duration.between(t, policy.autoConfirmDeadline(t)).toDays();
        long aftersale = java.time.Duration.between(t, policy.aftersaleDeadline(t)).toDays();
        assertThat(autoConfirm).isEqualTo(10L);
        assertThat(aftersale).isEqualTo(15L);
    }
}
