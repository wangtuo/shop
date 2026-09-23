package com.shop.api.marketing.enums;

/**
 * 优惠叠加层级（design.md 4.2.1）。试算引擎严格按 code 从小到大逐层计算，
 * 每层以「上一层之后的金额」为门槛/基数。
 *
 * <p>互斥规则（design.md 4.2.3）：秒杀独占 PRODUCT 层且互斥一切后续层；
 * 券三层（品类/店铺/平台）同层各限 1 张；SHOP 层满减与满折互斥取优惠最大者；
 * 拼团在 PRODUCT 层取拼团价后跳过券层与积分层；预售仅尾款阶段允许进入券层。
 */
public enum PromotionLayer {

    /** 商品级：限时折扣 / 秒杀价（二者互斥取最低；秒杀互斥一切） */
    PRODUCT(1, "商品级优惠"),
    /** 店铺级：满减 / 满折（互斥）/ 第 N 件优惠 */
    SHOP(2, "店铺级优惠"),
    /** 品类券：跨店，每单 1 张 */
    CATEGORY_COUPON(3, "品类券"),
    /** 店铺券：每单 1 张 */
    SHOP_COUPON(4, "店铺券"),
    /** 平台通用券：每单 1 张 */
    PLATFORM_COUPON(5, "平台券"),
    /** 积分抵现：100 积分=1 元，最高抵商品金额 50% */
    POINTS(6, "积分抵现");

    private final int code;
    private final String desc;

    PromotionLayer(int code, String desc) {
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
    public static PromotionLayer of(Integer code) {
        if (code == null) {
            return null;
        }
        for (PromotionLayer value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
