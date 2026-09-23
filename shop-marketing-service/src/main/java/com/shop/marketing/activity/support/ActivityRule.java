package com.shop.marketing.activity.support;

import lombok.Data;

import java.util.List;

/**
 * t_activity.rule_json 对应规则。
 * 拼团：requiredPeople/leaderDiscountFen；预售：deposit/inflate/final；砍价/抽奖按需。
 */
@Data
public class ActivityRule {

    /** 拼团成团人数：2/3/5/10 */
    private Integer requiredPeople;
    /** 团长额外优惠（分） */
    private Long leaderDiscountFen;

    /** 预售定金（分） */
    private Long depositFen;
    /** 定金膨胀抵扣（分，定金 50 抵 100 → 100） */
    private Long inflateDeductFen;
    /** 尾款金额（分，膨胀后、券前） */
    private Long finalPayFen;
    /** 尾款支付窗口天数（默认 3 天，design.md 4.5） */
    private Integer finalPayDays;

    /** 砍价原价/底价（分）/有效小时 */
    private Long originPriceFen;
    private Long floorPriceFen;
    private Integer bargainExpireHours;

    /** 抽奖消耗积分 */
    private Integer lotteryCostPoints;

    /* ===== W4-4/B4 C24：秒杀每用户可购件数（缺省 1；不新增 DDL，t_seckill_order 行内 qty 可为 N） ===== */

    /**
     * 秒杀每用户在同一活动场次内累计可购件数上限（按 status IN(0,1) 的 t_seckill_order
     * 行 SUM(qty) 校验）。缺省 1：存量活动与无该字段的 rule_json 行为不回退；
     * uk(activity_id,user_id,deleted) 仍保留，作为「同一用户每活动仅一行」的 1 单兜底。
     */
    private Integer perUserBuyLimit;

    /* ===== W4-3/B3 追加：砍价帮砍与积分抽奖规则（独立块，勿删改他人字段） ===== */

    /** 砍价关联 SKU（C 端普通下单 orderType=1 + CalcItem.activityPriceFen 用） */
    private Long bargainSkuId;
    /** 单次帮砍最小金额（分，含） */
    private Long bargainCutMinFen;
    /** 单次帮砍最大金额（分，含） */
    private Long bargainCutMaxFen;
    /** 帮砍人数上限 */
    private Integer bargainHelpLimit;

    /** 每人每日抽奖次数上限 */
    private Integer lotteryDailyLimit;
    /** 抽奖奖品配置（后台保存时同步 t_lottery_prize_stock；抽奖命中/库存以库存表为准） */
    private List<LotteryPrize> prizes;
}
