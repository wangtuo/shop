package com.shop.marketing.support;

import com.shop.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 卡 B2/API-M：三表状态机迁移矩阵穷举（审核态 + 业务态合法/非法/重复）。
 */
class MarketingStatusMachineTest {

    private static final int DRAFT = 0, PENDING = 1, APPROVED = 2, REJECTED = 3;

    // ---------------- 审核态 ----------------

    @Test
    void 审核态合法迁移() {
        assertDoesNotThrow(() -> MarketingStatusMachine.assertAuditTransition(DRAFT, PENDING));
        assertDoesNotThrow(() -> MarketingStatusMachine.assertAuditTransition(PENDING, APPROVED));
        assertDoesNotThrow(() -> MarketingStatusMachine.assertAuditTransition(PENDING, REJECTED));
        assertDoesNotThrow(() -> MarketingStatusMachine.assertAuditTransition(REJECTED, PENDING));
    }

    @Test
    void 审核态非法迁移() {
        // 草稿直接通过/驳回、待审核回草稿、通过后任何迁移、驳回直接通过、99 等
        assertThrows(BizException.class, () -> MarketingStatusMachine.assertAuditTransition(DRAFT, APPROVED));
        assertThrows(BizException.class, () -> MarketingStatusMachine.assertAuditTransition(DRAFT, REJECTED));
        assertThrows(BizException.class, () -> MarketingStatusMachine.assertAuditTransition(PENDING, DRAFT));
        assertThrows(BizException.class, () -> MarketingStatusMachine.assertAuditTransition(APPROVED, PENDING));
        assertThrows(BizException.class, () -> MarketingStatusMachine.assertAuditTransition(APPROVED, REJECTED));
        assertThrows(BizException.class, () -> MarketingStatusMachine.assertAuditTransition(REJECTED, APPROVED));
        assertThrows(BizException.class, () -> MarketingStatusMachine.assertAuditTransition(PENDING, PENDING));
        assertThrows(BizException.class, () -> MarketingStatusMachine.assertAuditTransition(99, PENDING));
        assertThrows(BizException.class, () -> MarketingStatusMachine.assertAuditTransition(DRAFT, 99));
    }

    @Test
    void 可提交态仅草稿与驳回() {
        org.junit.jupiter.api.Assertions.assertTrue(MarketingStatusMachine.canSubmit(DRAFT));
        org.junit.jupiter.api.Assertions.assertTrue(MarketingStatusMachine.canSubmit(REJECTED));
        org.junit.jupiter.api.Assertions.assertFalse(MarketingStatusMachine.canSubmit(PENDING));
        org.junit.jupiter.api.Assertions.assertFalse(MarketingStatusMachine.canSubmit(APPROVED));
    }

    // ---------------- 活动业务态：0下架 1进行中 2已结束 3已取消 ----------------

    @Test
    void 活动合法迁移() {
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.ACTIVITY, 0, 1));
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.ACTIVITY, 1, 0));
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.ACTIVITY, 1, 2));
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.ACTIVITY, 0, 3));
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.ACTIVITY, 1, 3));
    }

    @Test
    void 活动非法迁移() {
        // 99、已结束复活、已取消复活、下架直接结束
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.ACTIVITY, 0, 99));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.ACTIVITY, 2, 1));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.ACTIVITY, 2, 0));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.ACTIVITY, 3, 1));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.ACTIVITY, 3, 0));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.ACTIVITY, 0, 2));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.ACTIVITY, 0, 0));
    }

    // ---------------- 促销业务态：0停用 1启用 ----------------

    @Test
    void 促销合法迁移() {
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.PROMO, 0, 1));
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.PROMO, 1, 0));
    }

    @Test
    void 促销非法迁移() {
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.PROMO, 0, 99));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.PROMO, 0, 2));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.PROMO, 1, 2));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.PROMO, 0, 0));
    }

    // ---------------- 券业务态：0下架 1上架 2作废 ----------------

    @Test
    void 券合法迁移() {
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.COUPON, 0, 1));
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.COUPON, 1, 0));
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.COUPON, 0, 2));
        assertDoesNotThrow(() -> biz(MarketingStatusMachine.Domain.COUPON, 1, 2));
    }

    @Test
    void 券非法迁移() {
        // 作废复活 2→1/2→0、99、重复态
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.COUPON, 2, 1));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.COUPON, 2, 0));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.COUPON, 0, 99));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.COUPON, 1, 3));
        assertThrows(BizException.class, () -> biz(MarketingStatusMachine.Domain.COUPON, 0, 0));
    }

    // ---------------- 审核闸门 ----------------

    @Test
    void 审核未通过禁止任何业务态变更() {
        // 待审核/草稿/驳回/null 即使业务边合法也一律 CONFLICT
        for (Integer audit : new Integer[]{null, DRAFT, PENDING, REJECTED}) {
            BizException ex = assertThrows(BizException.class,
                    () -> MarketingStatusMachine.assertBizTransition(
                            MarketingStatusMachine.Domain.COUPON, audit, 0, 1));
            org.junit.jupiter.api.Assertions.assertEquals(10005, ex.getCode());
        }
    }

    private void biz(MarketingStatusMachine.Domain d, int from, int to) {
        MarketingStatusMachine.assertBizTransition(d, APPROVED, from, to);
    }
}
