package com.shop.api.pay.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付单对外契约（design 6.2 / 6.3）。
 *
 * <p>金额一律为 Long 分（CONTRACTS.md §2.2）；
 * status 取值见 {@code com.shop.api.pay.enums.PayStatuses}
 * （10 待支付 20 支付中 30 成功 40 失败 50 已关闭 60 退款中 70 已退款）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 支付单号（CONTRACTS.md §6：{@code P}+17 位） */
    private String payNo;

    /** 业务订单号 */
    private String orderNo;

    /** 付款用户 ID */
    private Long userId;

    /** 支付方式，取值见 PayMethods（1 微信 … 7 白条） */
    private Integer payMethod;

    /** 支付金额，单位：分 */
    private Long amountFen;

    /** 支付单状态，取值见 PayStatuses（10 待支付 … 70 已退款） */
    private Integer status;

    /** 第三方支付链接 / 收银台 URL（扫码、H5、PC 场景返回） */
    private String payUrl;

    /** 渠道交易流水号（第三方回调成功后写入，对账主键之一） */
    private String channelTransactionNo;

    /** 支付单创建时间 */
    private LocalDateTime createTime;

    /** 支付成功时间（渠道回调验签通过并落单的时间） */
    private LocalDateTime payTime;
}
