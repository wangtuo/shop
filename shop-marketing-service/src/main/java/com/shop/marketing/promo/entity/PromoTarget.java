package com.shop.marketing.promo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 促销作用目标：targetType 1SKU 2SPU 3三级类目。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_promo_target")
public class PromoTarget extends BaseEntity {

    private Long promoId;
    private Integer targetType;
    private Long targetId;
}
