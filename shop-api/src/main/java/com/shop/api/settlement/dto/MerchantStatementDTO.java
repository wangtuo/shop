package com.shop.api.settlement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商户结算单 DTO（按商户等级结算周期汇总的对账单据）。
 *
 * <p>金额单位统一为「分」（{@code Long}）。
 *
 * <p>规则来源：design.md 7.3 清算时机、7.3.2 结算周期
 * （S 级 T+1 提现 T+0；A 级 T+7 提现 T+1；B 级 T+15 提现 T+1；C 级 T+30 提现 T+3）。
 *
 * <p>{@code merchantLevel} 见 {@code com.shop.api.settlement.enums.MerchantLevels}，
 * {@code stage} 见 {@code com.shop.api.settlement.enums.ClearingStages}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantStatementDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 结算单号（ST 前缀，CONTRACTS.md §6） */
    private String statementNo;

    /** 商户 ID */
    private Long merchantId;

    /** 商户等级：0=S 1=A 2=B 3=C（决定结算周期与提现时效） */
    private Integer merchantLevel;

    /** 结算阶段：见 ClearingStages（20 待结算 / 30 已结算） */
    private Integer stage;

    /** 结算单总金额（分，含全部已分账货款） */
    private Long totalFen;

    /** 已结算（可提现）金额（分） */
    private Long settledFen;

    /** 冻结中金额（分，尚未到达等级结算周期） */
    private Long freezingFen;

    /** 结算完成时间（冻结期满转可提现的时间） */
    private LocalDateTime settleTime;
}
