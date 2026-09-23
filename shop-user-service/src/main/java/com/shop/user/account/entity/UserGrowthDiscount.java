package com.shop.user.account.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 年末成长值折算记录（t_user_growth_discount）：(user_id, year) 唯一，本年仅执行一次。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_growth_discount")
public class UserGrowthDiscount extends BaseEntity {

    private Long userId;

    private Integer year;

    private Long beforeGrowth;

    /** 80% 向下取整，保底当前等级下限 */
    private Long afterGrowth;

    private Integer beforeLevel;

    /** 折算后等级，不允许降级 */
    private Integer afterLevel;
}
