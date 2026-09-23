package com.shop.pay.feature.refund.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 退款单（t_pay_refund，design 6.4）。
 * status 10 待退款 20 退款中 30 成功 40 失败 50 已冲正。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pay_refund")
public class RefundOrder extends BaseEntity {

    /** 退款单号 R+17位 */
    private String refundNo;
    private String payNo;
    private String orderNo;
    private String aftersaleNo;
    private Long userId;
    private Long amountFen;
    private Integer payMethod;
    /** 1 全额 2 部分 */
    private Integer refundType;
    /** 1 售后 2 价保 3 清算冲正 */
    private Integer source;
    private Integer operatorType;
    private Integer status;
    private String reason;
    private String failReason;
    private Integer retryCount;
    /** B8：最近一次主动查询渠道时间（RefundQueryJob 补偿） */
    private LocalDateTime lastQueryTime;
    /** B8：主动查询次数 */
    private Integer queryCount;
    private LocalDateTime finishTime;

    @Version
    private Integer version;
}
