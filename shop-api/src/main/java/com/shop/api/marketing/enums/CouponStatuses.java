package com.shop.api.marketing.enums;

/**
 * 用户优惠券状态码值（CONTRACTS.md §4 / design.md 4.3 生命周期）。
 *
 * <p>创建 → 发放 → 未使用；下单 lock 预核销为中间锁定态（库表状态），支付成功 confirm 转已使用，
 * 取消 release 退回未使用；超过有效期转已过期；平台撤回/用户删除转已作废。
 */
public enum CouponStatuses {

    /** 未使用（含下单释放退回） */
    UNUSED(0, "未使用"),
    /** 已使用（支付成功核销） */
    USED(1, "已使用"),
    /** 已过期（超过有效期） */
    EXPIRED(2, "已过期"),
    /** 已作废（平台撤回、用户删除） */
    INVALID(3, "已作废");

    private final int code;
    private final String desc;

    CouponStatuses(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 按码值反查枚举，未知码值返回 {@code null}。 */
    public static CouponStatuses of(Integer code) {
        if (code == null) {
            return null;
        }
        for (CouponStatuses value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
