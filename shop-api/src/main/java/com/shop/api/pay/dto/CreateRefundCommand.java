package com.shop.api.pay.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建退款单命令（design 6.4 退款规则）。
 *
 * <p>退款必须原路退回；混合支付按各支付方式实付占比分别退回。多次部分退款累计不得超过
 * 支付单实付金额，且同一订单明细累计退款不超过其实付金额。金额一律 Long 分。</p>
 *
 * <ul>
 *     <li>refundType 取值见 {@code com.shop.api.pay.enums.RefundTypes}（1 全额 2 部分）；</li>
 *     <li>source 取值见 {@code com.shop.api.pay.enums.RefundSources}
 *     （1 售后 2 价保 3 清算冲正）。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateRefundCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 退款单号（CONTRACTS.md §6：{@code R}+17 位），为空时由支付域生成；非空时作为幂等键 */
    private String refundNo;

    /** 原业务订单号 */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /** 售后单号（售后触发时必填，CONTRACTS.md §6：{@code AS}+yyyyMMdd+10 位序列）；价保/清算场景可空 */
    private String aftersaleNo;

    /** 退款归属用户 ID（收款人） */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 本次退款金额，单位：分，必须大于 0，且累计退款不超过实付金额 */
    @NotNull(message = "退款金额不能为空")
    @Min(value = 1, message = "退款金额必须大于0")
    private Long amountFen;

    /** 退款支付方式/原支付方式，取值见 PayMethods；混合支付按各方式占比分拆时标识本次渠道 */
    private Integer payMethod;

    /** 退款类型，取值见 RefundTypes（1 全额退款 2 部分退款） */
    @NotNull(message = "退款类型不能为空")
    private Integer refundType;

    /** 退款来源，取值见 RefundSources（1 售后 2 价保 3 清算冲正） */
    @NotNull(message = "退款来源不能为空")
    private Integer source;

    /** 操作人类型，取值同统一用户类型码（-1 游客 0 普通用户 1 商户 2 平台运营），用于审计 */
    private Integer operatorType;

    /** 退款原因（用户申请原因 / 价保说明 / 冲正备注） */
    private String reason;
}
