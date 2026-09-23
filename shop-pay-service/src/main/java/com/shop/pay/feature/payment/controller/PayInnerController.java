package com.shop.pay.feature.payment.controller;

import com.shop.api.pay.client.PayClient;
import com.shop.api.pay.dto.CreatePaymentCommand;
import com.shop.api.pay.dto.CreateRefundCommand;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.pay.dto.RefundDTO;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.framework.web.Anonymous;
import com.shop.pay.feature.payment.service.PaymentService;
import com.shop.pay.feature.refund.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付域内部 Feign 接口，路径与 {@link PayClient} 逐字一致（/inner/pay）。
 *
 * <p>R4-23：类级 {@link Anonymous} 必不可少——拼团失败退款（GroupbuyOrderFlowServiceImpl
 * 经 MQ 消费线程）与结算链路在无用户上下文的线程内访问本控制器，漏标即恒 401，
 * 退款无法发起并毒化按 client 共享的熔断器。鉴权纵深不降级：
 * InternalTokenInterceptor 仍对 /inner/** 强制 X-Internal-Token。</p>
 */
@RestController
@Anonymous
@RequestMapping("/inner/pay")
@RequiredArgsConstructor
public class PayInnerController implements PayClient {

    private final PaymentService paymentService;
    private final RefundService refundService;

    @Override
    @PostMapping("/payment")
    public Result<PaymentDTO> createPayment(@Valid @RequestBody CreatePaymentCommand cmd) {
        return Result.success(paymentService.createPayment(cmd));
    }

    @Override
    @GetMapping("/payment")
    public Result<PaymentDTO> getByPayNo(@RequestParam("payNo") String payNo) {
        return Result.success(paymentService.getByPayNo(payNo));
    }

    @Override
    @GetMapping("/active-payment")
    public Result<PaymentDTO> getActiveByOrderNo(@RequestParam("orderNo") String orderNo) {
        // 对账兜底语义：无活跃单是正常结果（用户尚未重新发起），返回 data=null 而非 NOT_FOUND
        return Result.success(paymentService.findActiveByOrderNo(orderNo));
    }

    @Override
    @PostMapping("/refund")
    @AuditLog(action = "REFUND_INITIATE", targetType = "REFUND",
            targetIdSpEL = "#cmd.refundNo", captureArgs = true)
    public Result<RefundDTO> refund(@Valid @RequestBody CreateRefundCommand cmd) {
        return Result.success(refundService.refund(cmd));
    }
}
