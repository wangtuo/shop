package com.shop.pay.feature.payment.dto;

import lombok.Data;

import java.util.List;

/**
 * C 端发起支付请求：支持普通支付、组合支付（parts 多手段）、好友代付（friendUserId）。
 * 金额单位：分。parts 为空时按 payMethod + amountFen 单手段支付。
 */
@Data
public class PayCreateRequest {

    /** 业务订单号（幂等键） */
    private String orderNo;
    private Long userId;
    /** 好友代付实际付款人（非空表示好友代付场景） */
    private Long friendUserId;
    /** 单手段支付方式 PayMethods 码值 */
    private Integer payMethod;
    /** 单手段支付金额/组合支付总金额（分），组合时必须等于 parts 金额合计 */
    private Long amountFen;
    private String subject;
    /** 终端 Terminals 码值 */
    private Integer terminal;
    /**
     * 支付场景 PayScenes 码值（4=保证金缴费）。仅内部 Feign 命令
     * {@code CreatePaymentCommand}（服务端构造）允许指定；C 端 /pays 入口强制忽略请求体该字段，
     * 场景由 parts/friendUserId 推导，防止前端伪造场景分流。
     */
    private Integer payScene;
    /** 组合支付各手段明细 */
    private List<PayPart> parts;

    @Data
    public static class PayPart {
        /** 支付手段 PayMethods 码值 */
        private Integer payMethod;
        /** 本手段支付金额（分） */
        private Long amountFen;
    }
}
