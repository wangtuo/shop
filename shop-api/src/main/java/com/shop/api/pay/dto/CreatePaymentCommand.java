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
 * 创建支付单命令：订单提交成功后由订单域调用支付域发起支付（design 6.2）。
 *
 * <p>金额一律为 Long 分（CONTRACTS.md §2.2），必须与订单实付金额一致；
 * payMethod 取值见 {@code com.shop.api.pay.enums.PayMethods}，
 * terminal 取值见 {@code com.shop.api.pay.enums.Terminals}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePaymentCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务订单号（CONTRACTS.md §6：YYMMDD+业务类型2位+用户ID后4位+6位序列，共 18 位），幂等键 */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /** 付款用户 ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 支付方式，取值见 PayMethods（1 微信 2 支付宝 3 余额 4 银行卡 5 云闪付 6 花呗 7 白条） */
    @NotNull(message = "支付方式不能为空")
    private Integer payMethod;

    /** 支付金额，单位：分，必须等于订单实付金额且大于 0 */
    @NotNull(message = "支付金额不能为空")
    @Min(value = 1, message = "支付金额必须大于0")
    private Long amountFen;

    /** 订单主题/商品标题，透传给第三方渠道展示 */
    private String subject;

    /** 发起终端，取值见 Terminals（1 APP 2 H5 3 小程序 4 PC） */
    private Integer terminal;

    /** 支付场景，取值见 {@code com.shop.api.pay.enums.PayScenes}：1 普通商品 2 组合 3 好友代付 4 保证金缴费；null 按 1 处理 */
    private Integer payScene;
}
