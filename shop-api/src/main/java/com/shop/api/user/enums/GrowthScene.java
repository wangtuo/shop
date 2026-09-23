package com.shop.api.user.enums;

/**
 * 成长值获取场景常量。
 *
 * <p>规则来源：design.md 2.1.3 成长值规则。
 */
public final class GrowthScene {

    /** 消费：1 元 = 1 成长值 */
    public static final int CONSUME = 1;

    /** 评价：+10 成长值/单 */
    public static final int COMMENT = 2;

    /** 晒单：+20 成长值/单 */
    public static final int SHOW_ORDER = 3;

    /** 连续签到 7 天：+50 成长值 */
    public static final int SIGN_WEEK = 4;

    private GrowthScene() {
    }
}
