package com.shop.marketing.promo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 促销规则档位。
 * 满减：thresholdFen + reduceFen（多级取达最高档）；满折/限时折扣：thresholdFen + discountBp；
 * 满赠：thresholdFen + giftSkuId + giftQty；第N件：nthIndex + discountBp。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_promo_level")
public class PromoLevel extends BaseEntity {

    private Long promoId;
    private Long thresholdFen;
    private Long reduceFen;
    /** 折扣基点 950=9.5折 */
    private Integer discountBp;
    /** 第 N 件序号（2/3...），0 不适用 */
    private Integer nthIndex;
    private Long giftSkuId;
    private Integer giftQty;
}
