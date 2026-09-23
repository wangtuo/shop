package com.shop.settlement.clearing.support;

import com.shop.settlement.clearing.event.RefundShortfallEvent;

/**
 * 穿仓缺口告警通道（P0-1）。
 *
 * <p>默认实现 {@link LoggingAlarmNotifier} 仅打结构化 error 日志；
 * 真实钉钉/飞书/ITSM webhook 为环境残留（配置 {@code shop.settle.alarm.webhook}），
 * 任一实现均<b>不得</b>让通道故障回滚消费（调用方 catch 后落 remark 并 ACK）。
 */
public interface AlarmNotifier {

    /** 缺口工单首次告警（以及扫表发现滞留 10 态工单的补告警）。 */
    void notifyShortfall(RefundShortfallEvent event);
}
