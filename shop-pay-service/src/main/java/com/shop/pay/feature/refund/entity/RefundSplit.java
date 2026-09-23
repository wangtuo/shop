package com.shop.pay.feature.refund.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 退款拆分明细（t_pay_refund_split）：混合支付按实付占比原路退回，守恒不差 1 分。
 * status 10 待退款 20 退款中 30 成功 40 失败。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pay_refund_split")
public class RefundSplit extends BaseEntity {

    private String refundNo;
    private String payNo;
    private Integer payMethod;
    private String channelCode;
    private Long amountFen;
    private String channelRefundNo;
    private Integer status;
    private LocalDateTime finishTime;
}
