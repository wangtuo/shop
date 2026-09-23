package com.shop.api.marketing.enums;

/**
 * 拼团状态操作类型（GroupbuyEvent.type，design.md 4.4）。
 *
 * <p>团长 open 开团 → 团员 join 参团（同一用户同一活动仅 1 次）→ 24 小时内人数达标 success 成团、
 * 否则 fail 失败自动退款；2/3/5/10 人团由 requiredPeople 表达。拼团订单不支持券与积分（design.md 4.2.3）。
 */
public enum GroupbuyOpType {

    /** 开团：团长创建团 */
    OPEN(1, "开团"),
    /** 参团：团员加入团 */
    JOIN(2, "参团"),
    /** 成团：有效期内人数达标，团员订单转待发货 */
    SUCCESS(3, "成团"),
    /** 失败：到期人数不足，自动退款关单 */
    FAIL(4, "失败");

    private final int code;
    private final String desc;

    GroupbuyOpType(int code, String desc) {
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
    public static GroupbuyOpType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (GroupbuyOpType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
