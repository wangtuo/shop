package com.shop.api.settlement.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 提现结果事件（审核 / 打款终态通知）。
 *
 * <p>规则来源：design.md 7.4 提现规则：最低提现 100 元；单日上限 50 万元；
 * 每自然月前 3 次免手续费，超出按 0.1% 收取且单笔最低 2 元；
 * 到账时效 T+0 / T+1 / T+3（按商户等级）。
 *
 * <p>{@code status} 见 {@code com.shop.api.settlement.enums.WithdrawStatuses}
 * （30 成功 / 40 失败 / 50 拒绝）；失败或拒绝时 {@code failReason} 必填，
 * 失败金额退回商户可提现余额。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class WithdrawResultEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 提现单号（WD 前缀，CONTRACTS.md §6） */
    private String withdrawNo;

    /** 商户 ID */
    private Long merchantId;

    /** 提现金额，单位分 */
    private Long amountFen;

    /** 提现状态：见 WithdrawStatuses（30 成功 / 40 失败 / 50 拒绝） */
    private Integer status;

    /** 失败 / 拒绝原因（成功时为空） */
    private String failReason;
}
