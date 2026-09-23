package com.shop.pay.feature.refund.service;

import com.shop.api.pay.dto.CreateRefundCommand;
import com.shop.api.pay.dto.RefundDTO;
import com.shop.framework.web.LoginUser;
import com.shop.pay.feature.payment.dto.RefundNotifyParams;

/**
 * 退款领域服务（design 6.4：原路退回、余额实时退、混合支付按比例拆分、累计不超实付）。
 */
public interface RefundService {

    /** 内部 Feign：发起退款，refundNo 幂等。C 端不得直接调用（售后流程经 /inner/pay/refund 触发）。 */
    RefundDTO refund(CreateRefundCommand command);

    /** 对外查询：平台运营或退款单关联订单的归属商户可查，其余身份 403。 */
    RefundDTO getByRefundNoForViewer(String refundNo, LoginUser viewer);

    /** 失败退款重试（仅平台运营触发）。 */
    RefundDTO retry(String refundNo);

    /**
     * B8：渠道退款异步回调受理（POST /notify/refund/{channel}）。
     * 验签 + notify_log 幂等 + 金额校验后只做状态受理，终态统一进 RefundConvergeService 漏斗。
     */
    RefundDTO handleRefundNotify(RefundNotifyParams params);

    /**
     * B8：RefundQueryJob 扫描 PROCESSING(20) 退款单并主动查询渠道补偿。
     *
     * @return 本轮推进/查询的退款单数
     */
    int scanProcessingRefunds(int limit);
}
