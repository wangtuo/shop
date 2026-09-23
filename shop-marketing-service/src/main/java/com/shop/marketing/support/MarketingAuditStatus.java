package com.shop.marketing.support;

/**
 * 营销三表（t_promo/t_coupon/t_activity）审核态（marketing V5 D1）。
 * 存量数据默认 {@link #APPROVED}，保持平台直建/历史数据行为不回退。
 */
public enum MarketingAuditStatus {

    /** 草稿：商户保存后、提交前 */
    DRAFT(0),
    /** 待审核：商户提交后、平台审批前 */
    PENDING(1),
    /** 通过：仅该状态允许业务上下架（启用/上架/进行中） */
    APPROVED(2),
    /** 驳回：商户修改后可再次提交（3→1） */
    REJECTED(3);

    private final int code;

    MarketingAuditStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
