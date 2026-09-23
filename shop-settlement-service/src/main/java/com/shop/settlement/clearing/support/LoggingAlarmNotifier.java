package com.shop.settlement.clearing.support;

import com.shop.settlement.clearing.event.RefundShortfallEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 默认告警器：结构化 error 日志 + 指标埋点位。
 *
 * <p>{@code shop.settle.alarm.webhook} 缺省为空（仅日志）；配置非空时真实 webhook
 * 投递属环境残留（钉钉/飞书/ITSM 随部署环境而定），本期不发起真实 HTTP 调用，
 * 仅在日志中携带 webhook 配置位以便排障。
 */
@Slf4j
@Component
public class LoggingAlarmNotifier implements AlarmNotifier {

    @Value("${shop.settle.alarm.webhook:}")
    private String webhook;

    @Override
    public void notifyShortfall(RefundShortfallEvent e) {
        log.error("[SHORTFALL_ALARM] merchantId={} orderNo={} refundNo={} reverseNo={} "
                        + "merchantPartFen={} fromPendingFen={} fromAvailableFen={} fromDepositFen={} "
                        + "shortfallFen={} webhookConfigured={}",
                e.getMerchantId(), e.getOrderNo(), e.getRefundNo(), e.getReverseNo(),
                e.getMerchantPartFen(), e.getFromPendingFen(), e.getFromAvailableFen(),
                e.getFromDepositFen(), e.getShortfallFen(),
                webhook != null && !webhook.isBlank());
    }
}
