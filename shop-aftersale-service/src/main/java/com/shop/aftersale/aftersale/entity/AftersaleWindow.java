package com.shop.aftersale.aftersale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 售后可申请窗口（ORDER_SHIPPED / ORDER_CONFIRMED 事件投影）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_aftersale_window")
public class AftersaleWindow extends BaseEntity {

    private String orderNo;
    private Long userId;
    private Long merchantId;
    private Integer orderStatus;
    private Integer orderType;
    private LocalDateTime shippedTime;
    private LocalDateTime autoConfirmDeadline;
    private LocalDateTime confirmTime;
    private LocalDateTime freeAftersaleDeadline;
    private Integer warrantyDays;
    private LocalDateTime warrantyDeadline;
    private Long productPayFen;
    private Long freightFen;
    private Long usedPointsFen;
    private Integer usedPoints;
    private Integer hasFreightInsurance;
    /** 运费险保费快照（分，B11，V3 DDL） */
    private Long premiumFen;
}
