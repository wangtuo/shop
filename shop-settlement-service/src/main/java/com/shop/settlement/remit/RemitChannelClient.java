package com.shop.settlement.remit;

/**
 * 代发渠道 SPI（GAP_PLAN_FUNDS B10）：银行卡 B2B 代发 / 支付宝单笔转账等出款渠道统一抽象。
 *
 * <p>资金链路铁律：</p>
 * <ul>
 *   <li>{@link #remit(RemitRequest)} 只表示渠道<b>受理</b>，任何实现都不得把受理当作打款成功；</li>
 *   <li>资金记账（提现解冻出款 / 保证金余额清零）只允许发生在 {@link #query(RemitQueryRequest)}
 *       返回 {@link RemitStatuses#SUCCESS} 且业务库 CAS 获胜之后；</li>
 *   <li>未联调的真实渠道实现必须直接抛 DEPENDENCY_FAIL，<b>零伪成功</b>；</li>
 *   <li>渠道侧以 bizNo（withdrawNo / depositLogNo）为幂等键，重试不重复代发。</li>
 * </ul>
 */
public interface RemitChannelClient {

    /** 是否支持该渠道（1 银行卡 2 支付宝）。 */
    boolean supports(Integer channel);

    /** 提交代发（事务外调用）。渠道异常时实现方抛 BizException(DEPENDENCY_FAIL)，由批次留待重试。 */
    RemitResult remit(RemitRequest request);

    /** 查询代发终态（查询补偿 Job 主动轮询）。 */
    RemitQueryResult query(RemitQueryRequest request);
}
