package com.shop.marketing.activity.lottery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 抽奖奖品配置与库存（t_lottery_prize_stock，V5）。
 * 该表无 create_time/update_time 列，故不继承 {@code BaseEntity}。
 */
@Data
@TableName("t_lottery_prize_stock")
public class LotteryPrizeStock implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long activityId;
    private String prizeCode;
    private String prizeName;
    /** 1 积分 2 优惠券 3 谢谢参与 */
    private Integer prizeType;
    /** prizeType=2 时发放的券模板 ID */
    private Long couponId;
    /** prizeType=1 时积分数量 */
    private Integer points;
    /** 中奖权重 */
    private Integer weight;
    /** 库存；0=不限量 */
    private Integer totalStock;
    /** 已发数量（条件更新防超发） */
    private Integer issuedCount;

    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
