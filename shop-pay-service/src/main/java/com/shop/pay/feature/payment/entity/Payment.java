package com.shop.pay.feature.payment.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 支付单（t_pay_order，design 6.2/6.3）。金额单位：分。
 * status 10 待支付 20 支付中 30 成功 40 失败 50 已关闭 60 退款中 70 已退款。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pay_order")
public class Payment extends BaseEntity {

    /** 支付单号 P+17位 */
    private String payNo;
    /** 业务订单号（与 activeSlot 组成幂等键 uk_order_active） */
    private String orderNo;
    /**
     * 活跃槽位（P2-5 墓碑槽位，见 sql/pay/V4__pay_multi_attempt.sql）：
     * 0=当前活跃支付单（每订单至多一条）；被取代的终态（FAIL/CLOSED）行置为自身 id（雪花值），
     * 释放活跃槽位后同一订单可再次发起支付，历史多行以 (order_no, active_slot=id) 共存。
     */
    private Long activeSlot;
    /** 付款归属用户 ID（订单用户） */
    private Long userId;
    /** 好友代付实际付款人 */
    private Long friendUserId;
    /** 主支付方式 PayMethods 码值 */
    private Integer payMethod;
    /** 支付场景 1普通 2组合 3好友代付 */
    private Integer payScene;
    private Integer terminal;
    private Long amountFen;
    /** 累计已退款金额 */
    private Long refundedFen;
    private String subject;
    private Integer status;
    private String payUrl;
    private String channelCode;
    private String channelOrderNo;
    private String channelTransactionNo;
    private String notifyId;
    private LocalDateTime expireTime;
    private LocalDateTime payTime;
    private LocalDateTime closeTime;
    private String failReason;

    @Version
    private Integer version;
}
