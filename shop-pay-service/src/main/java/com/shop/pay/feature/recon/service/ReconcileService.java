package com.shop.pay.feature.recon.service;

import com.shop.pay.feature.recon.entity.ReconBatch;
import com.shop.pay.feature.recon.entity.ReconDiff;

import java.time.LocalDate;
import java.util.List;

/**
 * T+1 对账服务（design 6.5）。
 */
public interface ReconcileService {

    /** 执行指定日期、指定渠道的对账（拉账单 → 比对 → 三类差错落库与补偿）。 */
    ReconBatch runReconcile(LocalDate reconDate, String channelCode);

    /** 补偿重试待处理差错，返回处理条数。 */
    int retryPendingDiffs(int limit);

    /** 单笔差错人工/自动处理。 */
    ReconDiff handleDiff(Long diffId);

    List<ReconDiff> listDiffs(Integer status);
}
