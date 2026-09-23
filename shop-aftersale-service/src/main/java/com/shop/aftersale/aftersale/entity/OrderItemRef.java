package com.shop.aftersale.aftersale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单明细售后余额投影：累计退款不超过实付；同时仅一笔进行中售后。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_aftersale_order_item_ref")
public class OrderItemRef extends BaseEntity {

    private String orderNo;
    private Long orderItemId;
    private Long userId;
    private Long skuId;
    private Long spuId;
    private Integer qty;
    private Long paidFen;
    private Long refundedFen;
    private String activeNo;
    private Integer warrantyDays;
}
