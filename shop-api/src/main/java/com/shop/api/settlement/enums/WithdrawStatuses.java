package com.shop.api.settlement.enums;

/**
 * 提现单状态码值。
 *
 * <p>规则来源：design.md 7.4 提现规则。提现门槛与限额：
 * <ul>
 *     <li>最低提现金额 100 元；</li>
 *     <li>单日提现上限 50 万元；</li>
 *     <li>每自然月前 3 次免手续费，第 4 次起按金额 0.1% 收取，单笔最低 2 元；</li>
 *     <li>到账时效按商户等级 T+0 / T+1 / T+3（见 {@link MerchantLevels}）；</li>
 *     <li>保证金余额低于 50% 时限制提现（design 7.6）。</li>
 * </ul>
 */
public final class WithdrawStatuses {

    /** 已申请：商户提交提现申请，待审核 */
    public static final int APPLY = 10;

    /** 审核中：风控 / 财务审核 */
    public static final int AUDITING = 20;

    /** 提现成功：已打款到银行卡 / 支付宝 */
    public static final int SUCCESS = 30;

    /** 提现失败：渠道打款失败，可重新发起 */
    public static final int FAIL = 40;

    /** 已拒绝：审核不通过（如超限、保证金不足、风控） */
    public static final int REFUSED = 50;

    private WithdrawStatuses() {
    }
}
