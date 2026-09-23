package com.shop.settlement.withdraw.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 提现统计（t_sett_withdraw_daily_count）：每自然月免费 3 笔 + 单日 50 万额度。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_withdraw_daily_count")
public class SettWithdrawDailyCount extends BaseEntity {

    private Long merchantId;
    /** yyyy-MM */
    private String statMonth;
    private Integer applyCount;
    private Integer chargedCount;
    private Long dailyAmountFen;
    private LocalDate statDate;
}
