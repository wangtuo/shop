package com.shop.pay.feature.payment.controller;

import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.pay.dto.RefundDTO;
import com.shop.common.result.Result;
import com.shop.pay.feature.payment.dto.ChannelNotifyParams;
import com.shop.pay.feature.payment.dto.PayNotifyRequest;
import com.shop.pay.feature.payment.dto.RefundNotifyParams;
import com.shop.pay.feature.payment.dto.RefundNotifyRequest;
import com.shop.pay.feature.payment.service.PaymentService;
import com.shop.pay.feature.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 各支付渠道统一异步回调端点（mock 渠道：MOCK_WECHAT / MOCK_ALIPAY / MOCK_BANK /
 * MOCK_UQR / MOCK_HUABEI / MOCK_BAITIAO）。
 *
 * <p>design 6.2：回调必须先验签（HMAC-SHA256，PAY_SIGN_ERROR 60002 拒绝），再幂等，
 * 再金额/单状态校验；严禁信任前端回跳。</p>
 *
 * <p>P2-2：不使用切面 {@code @Idempotent}。渠道按对账策略重发同一通知（甚至更换 notifyId）
 * 属正常行为，必须由服务层 t_pay_notify_log 幂等并返回成功 ACK；切面 24h REPEAT_SUBMIT
 * 会让渠道拿不到成功响应而持续重试、刷告警。</p>
 */
@RestController
@RequestMapping("/notify")
@RequiredArgsConstructor
public class ChannelNotifyController {

    private final PaymentService paymentService;
    private final RefundService refundService;

    @PostMapping("/pay/{channel}")
    public Result<PaymentDTO> payNotify(@PathVariable("channel") String channel,
                                        @RequestBody PayNotifyRequest request) {
        ChannelNotifyParams params = ChannelNotifyParams.builder()
                .channelCode(channel)
                .notifyId(request.getNotifyId())
                .payNo(request.getPayNo())
                .channelTxnNo(request.getChannelTxnNo())
                .amountFen(request.getAmountFen())
                .status(request.getStatus())
                .sign(request.getSign())
                .paidTime(request.getPaidTime())
                .notifyType(1)
                .build();
        return Result.success(paymentService.handleNotify(params));
    }

    /**
     * B8：各支付渠道统一退款异步回调端点。顺序固定：验签（失败 60002）→
     * t_pay_notify_log（notify_type=2、refund_no 落列）幂等 → 金额一致校验 →
     * 只做状态受理，终态统一进 RefundConvergeService（与同步/查询同一 CAS 漏斗）。
     */
    @PostMapping("/refund/{channel}")
    public Result<RefundDTO> refundNotify(@PathVariable("channel") String channel,
                                          @RequestBody RefundNotifyRequest request) {
        RefundNotifyParams params = RefundNotifyParams.builder()
                .channelCode(channel)
                .notifyId(request.getNotifyId())
                .refundNo(request.getRefundNo())
                .channelRefundNo(request.getChannelRefundNo())
                .amountFen(request.getAmountFen())
                .status(request.getStatus())
                .sign(request.getSign())
                .finishTime(request.getFinishTime())
                .notifyType(2)
                .build();
        return Result.success(refundService.handleRefundNotify(params));
    }
}
