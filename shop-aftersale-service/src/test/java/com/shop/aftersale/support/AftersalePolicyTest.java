package com.shop.aftersale.support;

import com.shop.aftersale.aftersale.entity.AftersaleWindow;
import com.shop.api.aftersale.enums.AftersaleTypes;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后时限矩阵 / 申请规则 / 价保 / 运费险（design 8.3 / 8.5 / 8.6 / 8.8）。
 */
class AftersalePolicyTest {

    private final AftersalePolicy policy = new AftersalePolicy();

    private AftersaleWindow window(int status, LocalDateTime freeDeadline, LocalDateTime warrantyDeadline) {
        AftersaleWindow w = new AftersaleWindow();
        w.setOrderStatus(status);
        w.setOrderType(1);
        w.setFreeAftersaleDeadline(freeDeadline);
        w.setWarrantyDeadline(warrantyDeadline);
        w.setCreateTime(LocalDateTime.now().minusDays(1));
        return w;
    }

    @Test
    void 超时矩阵_审核2天_收货3天_换货发货5天() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 16, 10, 0);
        assertEquals(LocalDateTime.of(2026, 9, 18, 10, 0), policy.auditDeadline(t));
        assertEquals(LocalDateTime.of(2026, 9, 19, 10, 0), policy.receiveDeadline(t));
        assertEquals(LocalDateTime.of(2026, 9, 21, 10, 0), policy.exchangeShipDeadline(t));
    }

    @Test
    void arbitrateDeadline_跳过周末_5个工作日() {
        // 基准 2026-09-16 周三 → 之后 5 个工作日：17 周四、18 周五、21 一、22 二、23 三
        assertEquals(LocalDateTime.of(2026, 9, 23, 10, 0),
                policy.arbitrateDeadline(LocalDateTime.of(2026, 9, 16, 10, 0)));
        assertEquals(LocalDateTime.of(2026, 9, 19, 10, 0),
                policy.evidenceDeadline(LocalDateTime.of(2026, 9, 16, 10, 0)));
    }

    @Test
    void checkApplicable_发货前仅仅退款() {
        assertNull(policy.checkApplicable(AftersaleTypes.REFUND_ONLY, 20, window(20, null, null), null));
        assertTrue(policy.checkApplicable(AftersaleTypes.RETURN_REFUND, 20, window(20, null, null), null)
                .contains("仅支持仅退款"));
        assertTrue(policy.checkApplicable(AftersaleTypes.EXCHANGE, 20, window(20, null, null), null) != null);
    }

    @Test
    void checkApplicable_收货前仅退款退货退款补发() {
        assertNull(policy.checkApplicable(AftersaleTypes.REFUND_ONLY, 30, window(30, null, null), null));
        assertNull(policy.checkApplicable(AftersaleTypes.RETURN_REFUND, 30, window(30, null, null), null));
        assertNull(policy.checkApplicable(AftersaleTypes.RESHIP, 30, window(30, null, null), null));
        assertTrue(policy.checkApplicable(AftersaleTypes.EXCHANGE, 30, window(30, null, null), null) != null);
    }

    @Test
    void checkApplicable_收货后15天与质保期() {
        LocalDateTime now = LocalDateTime.now();
        AftersaleWindow inFree = window(40, now.plusDays(10), now.plusDays(10));
        assertNull(policy.checkApplicable(AftersaleTypes.RETURN_REFUND, 40, inFree, null));
        assertNull(policy.checkApplicable(AftersaleTypes.EXCHANGE, 40, inFree, null));

        AftersaleWindow outFreeInWarranty = window(40, now.minusDays(1), now.plusDays(30));
        assertNull(policy.checkApplicable(AftersaleTypes.RETURN_REFUND, 40, outFreeInWarranty, null));

        AftersaleWindow expired = window(40, now.minusDays(20), now.minusDays(1));
        assertTrue(policy.checkApplicable(AftersaleTypes.RETURN_REFUND, 40, expired, null).contains("超出"));
        assertTrue(policy.checkApplicable(AftersaleTypes.EXCHANGE, 40, expired, null).contains("超出"));
    }

    @Test
    void checkApplicable_窗口期按收货时间计算_当天与第15天放行_第16天拒绝() {
        // 全新刚完成订单：deadline = 收货时间 + 15 天，起点取收货时间而非下单/投影创建时间
        LocalDateTime justConfirmed = LocalDateTime.now();
        AftersaleWindow fresh = window(40,
                policy.freeAftersaleDeadline(justConfirmed),
                policy.warrantyDeadline(justConfirmed, 15));
        assertNull(policy.checkApplicable(AftersaleTypes.RETURN_REFUND, 40, fresh, null));
        assertNull(policy.checkApplicable(AftersaleTypes.EXCHANGE, 40, fresh, null));
        assertNull(policy.checkApplicable(AftersaleTypes.RESHIP, 40, fresh, null));

        // 第 15 天当天仍在售后期内（边界含当天；截止时刻尚未到）
        LocalDateTime confirmed15DaysAgo = LocalDateTime.now().minusDays(15).plusMinutes(1);
        AftersaleWindow onLastDay = window(40,
                policy.freeAftersaleDeadline(confirmed15DaysAgo),
                policy.warrantyDeadline(confirmed15DaysAgo, 15));
        assertNull(policy.checkApplicable(AftersaleTypes.RETURN_REFUND, 40, onLastDay, null));

        // 第 16 天：售后期/质保期均已过，拒绝且不放松校验
        LocalDateTime confirmed16DaysAgo = LocalDateTime.now().minusDays(16);
        AftersaleWindow expired = window(40,
                policy.freeAftersaleDeadline(confirmed16DaysAgo),
                policy.warrantyDeadline(confirmed16DaysAgo, 15));
        assertTrue(policy.checkApplicable(AftersaleTypes.RETURN_REFUND, 40, expired, null).contains("超出"));
        assertTrue(policy.checkApplicable(AftersaleTypes.RESHIP, 40, expired, null).contains("超出"));
        assertTrue(policy.checkApplicable(AftersaleTypes.EXCHANGE, 40, expired, null).contains("超出"));
    }

    @Test
    void checkApplicable_已完成订单窗口缺失_拒绝而非误放行() {
        // 投影与订单实时数据都给不出截止时间时必须拒绝（防回归：不得把缺窗口当作窗口期内）
        assertTrue(policy.checkApplicable(AftersaleTypes.RETURN_REFUND, 40, window(40, null, null), null)
                .contains("超出"));
    }

    @Test
    void checkApplicable_非法订单状态拒绝() {
        assertTrue(policy.checkApplicable(AftersaleTypes.REFUND_ONLY, 10, window(10, null, null), null)
                .contains("不允许"));
        assertTrue(policy.checkApplicable(AftersaleTypes.REFUND_ONLY, 50, window(50, null, null), null)
                .contains("不允许"));
    }

    @Test
    void 价保_普通7天_大促30天_秒杀拼团排除() {
        assertEquals(7, policy.priceProtectDays(1));
        assertEquals(30, policy.priceProtectDays(4));
        assertFalse(policy.priceProtectSupported(2));
        assertFalse(policy.priceProtectSupported(3));
        assertTrue(policy.priceProtectSupported(1));
    }

    @Test
    void 价保窗口_以接单下单时间为起点_普通7天大促30天() {
        LocalDateTime now = LocalDateTime.now();
        // 刚下单 / 第 7 天当天：在价保期内（边界含当天）
        assertTrue(policy.inPriceProtectWindow(now, 1, now));
        assertTrue(policy.inPriceProtectWindow(now.minusDays(7), 1, now));
        // 第 8 天：超出普通价保期
        assertFalse(policy.inPriceProtectWindow(now.minusDays(8), 1, now));
        // 大促（预售）30 天：第 25 天可价保，第 31 天不可
        assertTrue(policy.inPriceProtectWindow(now.minusDays(25), 4, now));
        assertFalse(policy.inPriceProtectWindow(now.minusDays(31), 4, now));
        // 缺少下单时间无法判定（如投影缺失且订单实时数据无下单时间）→ 不放行
        assertFalse(policy.inPriceProtectWindow(null, 1, now));
    }

    @Test
    void checkApplicable_价保申请_按订单下单时间判定7天窗口() {
        LocalDateTime now = LocalDateTime.now();
        AftersaleWindow w = window(40, null, null);
        assertNull(policy.checkApplicable(AftersaleTypes.PRICE_PROTECT, 40, w, now.minusDays(3)));
        assertTrue(policy.checkApplicable(AftersaleTypes.PRICE_PROTECT, 40, w, now.minusDays(8))
                .contains("价保期"));
        // 大促 30 天
        w.setOrderType(4);
        assertNull(policy.checkApplicable(AftersaleTypes.PRICE_PROTECT, 40, w, now.minusDays(20)));
    }

    @Test
    void 价保差价_只取普通售价_不看活动价() {
        // 购买实付单价 10000；当前普通价 8000，秒杀价 5000（不允许取秒杀价）
        assertEquals(2000L, policy.priceProtectDiff(10000, 8000));
        // 当前普通价未降价 → 0
        assertEquals(0L, policy.priceProtectDiff(10000, 10000));
        assertEquals(0L, policy.priceProtectDiff(10000, 12000));
    }

    @Test
    void 运费险_封顶25元_责任资格_72小时() {
        assertEquals(2500L, policy.insuranceClaimFen(3000L));
        assertEquals(1200L, policy.insuranceClaimFen(1200L));
        assertTrue(policy.insuranceEligible(true, 1));
        assertTrue(policy.insuranceEligible(true, 2));
        assertFalse(policy.insuranceEligible(false, 1));

        LocalDateTime refund = LocalDateTime.of(2026, 9, 16, 10, 0);
        assertEquals(LocalDateTime.of(2026, 9, 19, 10, 0), policy.insuranceClaimDeadline(refund));
    }
}
