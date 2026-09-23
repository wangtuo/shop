package com.shop.aftersale.aftersale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 售后明细行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_aftersale_item")
public class AftersaleItem extends BaseEntity {

    private String aftersaleNo;
    private Long orderItemId;
    private Long skuId;
    private Long spuId;
    private String productName;
    private String skuSpec;
    private Integer qty;
    private Long paidFen;
    private Long refundFen;
}
