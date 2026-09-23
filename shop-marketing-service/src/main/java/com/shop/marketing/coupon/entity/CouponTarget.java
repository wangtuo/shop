package com.shop.marketing.coupon.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 券作用目标：targetType 1SKU 2SPU 3三级类目 4店铺。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_coupon_target")
public class CouponTarget extends BaseEntity {

    private Long couponId;
    private Integer targetType;
    private Long targetId;
}
