package com.shop.api.marketing.enums;

/**
 * 优惠券发放方式码值（design.md 4.3.1）。
 */
public enum CouponIssueWays {

    /** 主动领取：领券中心，每人每券限 1 张 */
    ACTIVE_CLAIM(1, "主动领取"),
    /** 活动发放：参与活动自动发放，按活动规则限领 */
    ACTIVITY(2, "活动发放"),
    /** 新人礼包：注册自动发放，仅 1 次 */
    NEW_USER(3, "新人礼包"),
    /** 系统补偿：客服手动发放，不限量 */
    COMPENSATE(4, "系统补偿"),
    /** 积分兑换：积分商城兑换，不限量 */
    POINTS_EXCHANGE(5, "积分兑换");

    private final int code;
    private final String desc;

    CouponIssueWays(int code, String desc) {
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
    public static CouponIssueWays of(Integer code) {
        if (code == null) {
            return null;
        }
        for (CouponIssueWays value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
