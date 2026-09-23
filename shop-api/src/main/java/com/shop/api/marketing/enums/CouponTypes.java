package com.shop.api.marketing.enums;

/**
 * 优惠券类型码值（CONTRACTS.md §4 / design.md 4.1.2）。
 *
 * <p>叠加层级（design.md 4.2.1）：品类券、店铺券属不同层级各限 1 张；
 * 满减券/折扣券/无门槛券按券的适用范围归属平台/店铺/品类层；免邮券仅抵扣运费。
 */
public enum CouponTypes {

    /** 满减券：有最低消费门槛，满 X 减 Y */
    FULL_REDUCE(1, "满减券"),
    /** 折扣券：打 X 折，可有/无门槛 */
    DISCOUNT(2, "折扣券"),
    /** 无门槛券：直接减 Y 元 */
    NO_THRESHOLD(3, "无门槛券"),
    /** 免邮券：抵扣运费，不计入商品优惠 */
    FREE_FREIGHT(4, "免邮券"),
    /** 品类券：限三级类目，跨店可叠加 1 张 */
    CATEGORY(5, "品类券"),
    /** 店铺券：限店铺，每单 1 张 */
    SHOP(6, "店铺券");

    private final int code;
    private final String desc;

    CouponTypes(int code, String desc) {
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
    public static CouponTypes of(Integer code) {
        if (code == null) {
            return null;
        }
        for (CouponTypes value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
