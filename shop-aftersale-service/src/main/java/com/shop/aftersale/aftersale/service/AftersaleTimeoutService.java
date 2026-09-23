package com.shop.aftersale.aftersale.service;

import com.shop.aftersale.support.AftersaleTimeoutMessage;

/**
 * 售后超时处理（MQ 延时消息与定时扫描共用同一套条件更新）。
 */
public interface AftersaleTimeoutService {

    void dispatch(AftersaleTimeoutMessage message);

    /**
     * 带消息级幂等流水的分发（P2-1）：t_aftersale_mq_consume 流水与五类业务动作
     * <b>同事务</b>写入；MQ 延时消息与 60s 扫表双路径以同一确定性 eventId 去重。
     */
    void dispatchTracked(AftersaleTimeoutMessage message);

    /** 运费险 72h 自动理赔到用户账户。 */
    void claimInsurance(Long insuranceId);

    /** 举证期截止：介入单 10 举证中 → 20 待裁决。 */
    void closeEvidence(String aftersaleNo);
}
