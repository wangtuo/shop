package com.shop.pay.feature.recon.service;

import com.shop.pay.channel.ChannelQueryResult;
import com.shop.pay.feature.recon.entity.ReconDiff;

/**
 * 对账差错逐笔处置器（P2-4）：每条差异在独立 REQUIRES_NEW 事务内落终态，
 * 单条失败仅回滚该条事务，兄弟差异已提交不回滚、整批不被污染。
 *
 * <p>本接口为独立 Spring Bean，调用方（ReconcileService 批循环/人工单笔触发）经代理调用，
 * 保证 {@code @Transactional(REQUIRES_NEW)} 真实生效（禁止 this 自调用）。</p>
 */
public interface ReconDiffHandler {

    /** 人工单笔触发：不带最新渠道查询结果（行为同批处理中渠道未返回有效信息的分支）。 */
    DiffHandleResult handleOne(ReconDiff diff);

    /**
     * 批处理/重试入口：短款的渠道主动查询结果由调用方在<b>事务外</b>经 ChannelRouter SPI 取得后传入
     * （规约 3：事务内禁止 Feign/HTTP；渠道异常由调用方按可重试失败记录，禁止伪平账）。
     *
     * @param shortQuery 短款差异的渠道查询结果；非短款或未查询传 null
     */
    DiffHandleResult handleOne(ReconDiff diff, ChannelQueryResult shortQuery);

    /**
     * 单条处置失败后的失败留痕：独立新事务内 retry_count+1 并保留失败原因，
     * 差异维持 10/20 可重试态（该提交不随失败事务回滚，兄弟行不受影响）。
     */
    void markFailure(ReconDiff diff, Exception error);

    /** 单条处置结果。 */
    record DiffHandleResult(Long diffId, Outcome outcome, String remark) {

        public enum Outcome {
            /** 已落终态（30 已处理 或 40 人工挂账）。 */
            HANDLED,
            /** 并发竞争落败（补单 CAS=0 / 重复键），视为已被并发处理，无异常向上。 */
            SKIPPED
        }
    }
}
