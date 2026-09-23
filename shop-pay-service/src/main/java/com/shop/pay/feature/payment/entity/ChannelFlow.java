package com.shop.pay.feature.payment.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 支付渠道流水（t_pay_channel_flow）：普通支付 1 条，组合支付 N 条（合计=支付单金额）。
 * flowStatus 10 待支付 20 处理中 30 成功 40 失败 50 已关闭。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pay_channel_flow")
public class ChannelFlow extends BaseEntity {

    private String payNo;
    private String orderNo;
    /** 本行支付手段 PayMethods 码值 */
    private Integer payMethod;
    private String channelCode;
    private String channelOrderNo;
    private String channelTransactionNo;
    private Long amountFen;
    private String payUrl;
    private Integer flowStatus;
    /** 本行累计已退款金额 */
    private Long paidFen;
    private String requestBody;
    private String responseBody;
    private LocalDateTime payTime;

    @Version
    private Integer version;
}
