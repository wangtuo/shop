package com.shop.user.account.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 积分冻结记录（t_user_points_freeze）：下单 TCC 与订单一一对应。
 * status：0 锁定中 1 已实扣 2 已释放。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_points_freeze")
public class UserPointsFreeze extends BaseEntity {

    private Long userId;

    /** 订单号（幂等键） */
    private String orderNo;

    /** 冻结积分个数 */
    private Long points;

    /** 抵现金额（分） */
    private Long deductFen;

    /** 场景（PointsScene） */
    private Integer scene;

    /** 0 锁定中 1 已实扣 2 已释放 */
    private Integer status;

    @Version
    private Integer version;
}
