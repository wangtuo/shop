package com.shop.api.user.enums;

/**
 * 积分变动类型常量（积分流水 change_type）。
 *
 * <p>规则来源：design.md 2.2.2 积分规则（获取/抵现/有效期）、CONTRACTS.md §5 积分冻结链路。
 */
public final class PointsChangeType {

    /** 获取（消费返、签到、评价等发放） */
    public static final int EARN = 1;

    /** 消耗（抵现/兑换/抽奖） */
    public static final int CONSUME = 2;

    /** 冻结（下单预扣，积分从可用转入冻结） */
    public static final int FREEZE = 3;

    /** 释放（订单取消/超时，冻结积分退回可用） */
    public static final int RELEASE = 4;

    /** 退回（退款按比例退回积分） */
    public static final int REFUND = 5;

    /** 过期清零（积分自获取起 365 天有效，到期清零） */
    public static final int EXPIRE = 6;

    private PointsChangeType() {
    }
}
