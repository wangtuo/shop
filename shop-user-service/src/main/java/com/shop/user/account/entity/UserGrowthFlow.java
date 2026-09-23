package com.shop.user.account.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 成长值流水（t_user_growth_flow）：biz_no 幂等。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_growth_flow")
public class UserGrowthFlow extends BaseEntity {

    private Long userId;

    private String bizNo;

    /** GrowthScene */
    private Integer scene;

    private Integer growth;

    private Long growthAfter;

    private Integer levelAfter;
}
