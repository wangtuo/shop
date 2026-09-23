package com.shop.aftersale.aftersale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 价保申请记录（单单一次）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_aftersale_price_protect")
public class PriceProtectRecord extends BaseEntity {

    private String orderNo;
    private String aftersaleNo;
    private Long orderItemId;
    private Long skuId;
    private Long originalPriceFen;
    private Long currentPriceFen;
    private Long diffFen;
    private Integer bigPromotion;
    private Integer status;
}
