package com.shop.api.settlement.enums;

/**
 * 清算阶段码值（清算单 / 结算单生命周期阶段）。
 *
 * <p>规则来源：CONTRACTS.md §4 清算阶段；design.md 7.3.1 触发节点。
 *
 * <ul>
 *     <li>支付成功：资金停留在平台收款账户，登记为 {@link #WAIT_CLEAR} 待清算；</li>
 *     <li>确认收货（或超时自动收货）：触发分账，进入 {@link #WAIT_SETTLE} 待结算（冻结中）；</li>
 *     <li>售后期结束且到达商户等级结算周期：{@link #SETTLED} 已结算，转为可提现余额；</li>
 *     <li>退款成功：settlement 消费 REFUND_SUCCESS 后内部冲正，置 {@link #REVERSED}，
 *     支付通道费不退（design 7.5.1）。</li>
 * </ul>
 */
public final class ClearingStages {

    /** 待清算：支付成功，资金在平台收款账户 */
    public static final int WAIT_CLEAR = 10;

    /** 待结算：已收货，已分账，按商户等级周期冻结 */
    public static final int WAIT_SETTLE = 20;

    /** 已结算：冻结期满，商户可提现 */
    public static final int SETTLED = 30;

    /** 已冲正：退款成功后按规则冲正（通道费不退回） */
    public static final int REVERSED = 40;

    private ClearingStages() {
    }
}
