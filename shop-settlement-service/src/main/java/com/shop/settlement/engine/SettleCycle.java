package com.shop.settlement.engine;

import com.shop.api.settlement.enums.MerchantLevels;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 结算周期纯函数组件（design 7.3.2）。
 *
 * <table>
 *   <caption>等级结算周期</caption>
 *   <tr><th>S</th><td>T+1（收货次日结算，提现 T+0）</td></tr>
 *   <tr><th>A</th><td>T+7（提现 T+1）</td></tr>
 *   <tr><th>B</th><td>T+15（售后期结束，提现 T+1）</td></tr>
 *   <tr><th>C</th><td>T+30（提现 T+3）</td></tr>
 * </table>
 */
@Component
public class SettleCycle {

    public static final int S_DAYS = 1;
    public static final int A_DAYS = 7;
    public static final int B_DAYS = 15;
    public static final int C_DAYS = 30;

    /** 按等级返回结算周期天数。 */
    public int settleDays(int level) {
        return switch (level) {
            case MerchantLevels.S -> S_DAYS;
            case MerchantLevels.A -> A_DAYS;
            case MerchantLevels.B -> B_DAYS;
            case MerchantLevels.C -> C_DAYS;
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "未知商户等级: " + level);
        };
    }

    /**
     * 计算应结算日期：确认收货日 T0 + 等级周期天数。
     */
    public LocalDate dueDate(int level, LocalDate confirmedDate) {
        if (confirmedDate == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "确认收货日期不能为空");
        }
        return confirmedDate.plusDays(settleDays(level));
    }
}
