package com.shop.settlement.deposit.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 保证金缴费发起结果：DP 流水号 + 支付单号 + 收银台链接（B10 三段式第一段返回）。 */
@Data
@AllArgsConstructor
public class DepositPayVO {

    /** 保证金流水号（同时作为支付域 orderNo，渠道/支付双侧幂等键） */
    private String logNo;
    /** 支付单号 */
    private String payNo;
    /** 第三方支付链接 / 收银台 URL */
    private String payUrl;
}
