package com.shop.settlement.enums;

/**
 * 账户流水变动类型（t_sett_account_flow.change_type）。
 * 与 biz_no 组合唯一保证幂等。
 */
public final class FlowChangeTypes {

    /** 清算货款记入商户待结算 */
    public static final int CLEARING_TO_PENDING = 10;
    /** 待结算转可提现（日终批） */
    public static final int PENDING_TO_AVAILABLE = 11;
    /** 平台佣金入账 */
    public static final int COMMISSION_INCOME = 12;
    /** 技术服务费入账 */
    public static final int TECH_FEE_INCOME = 13;
    /** 支付通道费入账 */
    public static final int CHANNEL_FEE_INCOME = 14;
    /** 营销补贴出账（营销账户承担平台优惠） */
    public static final int MARKETING_SUBSIDY_OUT = 15;
    /** 运费险保费入账（平台收入） */
    public static final int INSURANCE_PREMIUM_INCOME = 16;

    /** 提现冻结（可提现→冻结） */
    public static final int WITHDRAW_FREEZE = 20;
    /** 提现成功出款（冻结扣减） */
    public static final int WITHDRAW_REMIT = 21;
    /** 提现失败/拒绝退回（冻结→可提现） */
    public static final int WITHDRAW_RETURN = 22;
    /** 提现手续费入账（平台收入） */
    public static final int WITHDRAW_FEE_INCOME = 23;

    /** 退款冲正：扣商户待结算 */
    public static final int REFUND_FROM_PENDING = 30;
    /** 退款冲正：扣商户保证金（账户流水侧留痕，资金账见保证金流水） */
    public static final int REFUND_FROM_DEPOSIT = 31;
    /** 退款冲正：平台佣金回退 */
    public static final int COMMISSION_REVERSE = 32;
    /** 退款冲正：营销补贴退回营销账户 */
    public static final int SUBSIDY_REVERSE = 33;
    /** 退款冲正：扣商户可提现余额（P1-10 瀑布中间档：待结算→可提现→保证金） */
    public static final int REFUND_FROM_AVAILABLE = 34;

    /** 保证金缴纳（保证金流水使用） */
    public static final int DEPOSIT_PAY = 40;
    /** 保证金清退退还 */
    public static final int DEPOSIT_REFUND = 41;
    /** 保证金赔付扣款 */
    public static final int DEPOSIT_DEDUCT = 42;
    /** 保证金罚款收入（平台收入） */
    public static final int DEPOSIT_FINE_INCOME = 43;

    private FlowChangeTypes() {
    }
}
