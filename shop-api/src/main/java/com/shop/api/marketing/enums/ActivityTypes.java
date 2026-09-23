package com.shop.api.marketing.enums;

/**
 * 营销活动类型码值（CONTRACTS.md §4 / design.md 4.1）。
 *
 * <p>1~5 为促销类（满减/满折/满赠/第N件/限时折扣），10 起为活动类（秒杀/拼团/预售/砍价/抽奖）。
 */
public enum ActivityTypes {

    /** 满减：满 X 减 Y，可多级，可与券叠加 */
    FULL_REDUCE(1, "满减"),
    /** 满折：满 X 打 Y 折，与满减互斥 */
    FULL_DISCOUNT(2, "满折"),
    /** 满赠：满 X 赠指定商品（赠品 giftFlag=1） */
    FULL_GIFT(3, "满赠"),
    /** 第 N 件优惠：第 2 件半价/第 3 件 0 元 */
    NTH_PIECE(4, "第N件优惠"),
    /** 限时折扣：商品级打折，与满减/满折互斥 */
    LIMITED_DISCOUNT(5, "限时折扣"),
    /** 秒杀：独立秒杀库存，与所有其他优惠互斥 */
    SECKILL(10, "秒杀"),
    /** 拼团：多人成团，不可用券与积分 */
    GROUPBUY(11, "拼团"),
    /** 预售：定金 + 尾款，券仅尾款可用 */
    PRESALE(12, "预售"),
    /** 砍价：分享砍价底价购买 */
    BARGAIN(13, "砍价"),
    /** 抽奖：支付/积分参与 */
    LOTTERY(14, "抽奖");

    private final int code;
    private final String desc;

    ActivityTypes(int code, String desc) {
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
    public static ActivityTypes of(Integer code) {
        if (code == null) {
            return null;
        }
        for (ActivityTypes value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
