package com.shop.api.user.enums;

/**
 * 积分获取/使用场景常量。
 *
 * <p>规则来源：design.md 2.2.2 积分规则。
 */
public final class PointsScene {

    /** 消费返积分（实付金额 × 等级倍率） */
    public static final int CONSUME = 1;

    /** 签到（每日递增，第 7 天 50，单日上限 50） */
    public static final int SIGN = 2;

    /** 评价（20/条，带图 +10，单日上限 100） */
    public static final int COMMENT = 3;

    /** 分享（10/次，单日上限 20） */
    public static final int SHARE = 4;

    /** 晒单 */
    public static final int SHOW_ORDER = 5;

    /** 系统补偿（客服人工发放，不限量） */
    public static final int COMPENSATE = 6;

    private PointsScene() {
    }
}
