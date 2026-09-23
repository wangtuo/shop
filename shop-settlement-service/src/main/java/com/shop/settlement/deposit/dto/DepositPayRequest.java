package com.shop.settlement.deposit.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 保证金缴纳请求（B10：缴费走真实支付单，payScene=4）。 */
@Data
public class DepositPayRequest {

    @NotNull(message = "缴费金额不能为空")
    @Min(value = 1, message = "缴费金额必须大于0")
    private Long amountFen;

    /** 支付方式，取值见 com.shop.api.pay.enums.PayMethods（1 微信 … 7 白条）。 */
    @NotNull(message = "支付方式不能为空")
    private Integer payMethod;

    /** 发起终端，取值见 com.shop.api.pay.enums.Terminals（1 APP 2 H5 3 小程序 4 PC）。 */
    @NotNull(message = "支付终端不能为空")
    private Integer terminal;

    /**
     * 客户端幂等令牌（M-3，可选）：同商户同令牌窗口内拒绝重复提交；
     * 缺省时服务端以预生成的保证金流水号兜底。缴费 DP 流水以该令牌为 bizNo 防重。
     */
    @Size(max = 64)
    private String clientToken;
}
