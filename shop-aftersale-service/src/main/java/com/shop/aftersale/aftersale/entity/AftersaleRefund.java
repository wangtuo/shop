package com.shop.aftersale.aftersale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 售后退款单（调 PayClient.refund 前落单；REFUND_SUCCESS 回写，幂等）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_aftersale_refund")
public class AftersaleRefund extends BaseEntity {

    private String refundNo;
    private String aftersaleNo;
    private String orderNo;
    private Long userId;
    private Long amountFen;
    private Integer refundType;
    private Integer payMethod;
    private Integer status;
    private LocalDateTime refundTime;
    private String failReason;
}
