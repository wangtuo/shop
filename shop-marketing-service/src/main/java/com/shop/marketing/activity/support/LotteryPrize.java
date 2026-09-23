package com.shop.marketing.activity.support;

import lombok.Data;

import java.io.Serializable;

/**
 * ActivityRule.prizes 元素（C27）：砍价/抽奖活动 rule_json 内的奖品配置。
 * 抽奖命中与发奖库存以 t_lottery_prize_stock 表为准，此结构用于后台保存与规则展示。
 */
@Data
public class LotteryPrize implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 奖品编码（活动内唯一） */
    private String prizeCode;
    /** 奖品名称 */
    private String prizeName;
    /** 奖品类型：1 积分 2 优惠券 3 谢谢参与 */
    private Integer prizeType;
    /** prizeType=2 时发放的券模板 ID */
    private Long couponId;
    /** prizeType=1 时发放的积分数量 */
    private Integer points;
    /** 中奖权重（&gt;0） */
    private Integer weight;
    /** 库存；0=不限量 */
    private Integer totalStock;
}
