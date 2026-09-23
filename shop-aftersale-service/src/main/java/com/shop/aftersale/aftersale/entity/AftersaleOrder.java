package com.shop.aftersale.aftersale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 售后单主表实体（五类售后共用）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_aftersale_order")
public class AftersaleOrder extends BaseEntity {

    private String aftersaleNo;
    private String orderNo;
    private Long userId;
    private Long merchantId;
    private Integer type;
    private Integer status;
    private String reason;
    private Integer responsibilitySide;
    private LocalDateTime applyTime;

    private LocalDateTime shippedTime;
    private LocalDateTime confirmTime;
    private LocalDateTime freeAftersaleDeadline;
    private LocalDateTime warrantyDeadline;

    private LocalDateTime auditDeadline;
    private LocalDateTime auditTime;
    private LocalDateTime receiveDeadline;
    private LocalDateTime merchantReceiveTime;
    private LocalDateTime exchangeShipDeadline;

    private String rejectReason;
    private LocalDateTime rejectTime;
    private Integer resubmitTimes;
    private LocalDateTime cancelTime;
    private LocalDateTime interveneTime;

    private String returnLogisticsNo;
    private String returnCompany;
    private LocalDateTime returnShipTime;

    private Long exchangeSkuId;
    private String exchangeLogisticsNo;
    private String exchangeCompany;
    private LocalDateTime exchangeShipTime;
    private LocalDateTime exchangeReceiveTime;

    private Long refundFen;
    private Long freightRefundFen;
    private Integer pointsRefund;

    private String refundNo;
    private Integer refundType;
    private LocalDateTime refundTime;

    private Long originalPriceFen;
    private Long currentPriceFen;
    private LocalDateTime finishTime;

    @Version
    private Integer version;
}
