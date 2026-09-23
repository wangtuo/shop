package com.shop.settlement.deposit.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 保证金部分扣赔结果（P1-10 退款瀑布第三档）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepositDeductResult {

    /** 实际从保证金扣减金额（分，0 ~ 请求额） */
    private long actualFen;
    /** 扣减后保证金余额（分） */
    private long balanceAfterFen;
}
