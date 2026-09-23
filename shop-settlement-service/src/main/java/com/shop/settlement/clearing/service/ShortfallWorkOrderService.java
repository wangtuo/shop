package com.shop.settlement.clearing.service;

import com.shop.settlement.clearing.event.RefundShortfallEvent;

import java.time.LocalDate;

/**
 * 退款穿仓缺口工单服务（GAP_PLAN_FUNDS P0-1）：
 * 实时消费落单告警、outbox/relay 丢消息每日扫表兜底、保证金到账自动补扣。
 */
public interface ShortfallWorkOrderService {

    /** 消费组沿用 create-topics.sh 预建的 cg_sett_shortfall，勿改名。 */
    String CG_SHORTFALL = "cg_sett_shortfall";

    /**
     * 消费 shop_refund_shortfall：mq_consume 幂等 → 工单落库（uk_event_id / reverse_no）
     * → 获胜方 CAS 10→20、alert_count+1、调 {@code AlarmNotifier}。
     * 告警通道异常不回滚消费（落 remark 留痕，消息正常 ACK）。
     */
    void onShortfall(RefundShortfallEvent event);

    /**
     * 每日扫表兜底：分页扫 t_sett_clearing_reverse status=2（V4 idx_status），
     * 无工单的冲正以 RESIDUAL-+reverseNo 合成事件走同一落单逻辑；
     * 仍滞留 10 态的工单补一次告警。
     */
    void dailyRescan(LocalDate now);

    /**
     * 保证金缴费到账后自动补扣（B10 缴费确认事务内调用）：按工单时间序在新到账余额内
     * 补扣（保证金扣减 + DP log_type=20 biz_no=reverseNo + 账户流水 31，均幂等），
     * 补满工单 CAS→30，不足保留 10/20；事件重放不重复补扣。
     */
    void clawbackOnDepositPaid(long merchantId);
}
