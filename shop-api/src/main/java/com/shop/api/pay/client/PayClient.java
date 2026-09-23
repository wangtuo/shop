package com.shop.api.pay.client;

import com.shop.api.pay.dto.CreatePaymentCommand;
import com.shop.api.pay.dto.CreateRefundCommand;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.pay.dto.RefundDTO;
import com.shop.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 支付域跨服务 Feign 契约（CONTRACTS.md §3）。
 *
 * <p>对应服务 shop-pay-service 的内部接口前缀 {@code /inner/pay}，由订单、售后等域同步调用。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *     <li>design 6.2：第三方支付结果以异步回调为准，回调入口必须先验签、再做幂等校验，
 *     支付域只负责验签 / 幂等 / 落单，随后发出 ORDER_PAID 事件由各域异步消费；</li>
 *     <li>design 6.4：{@link #refund} 必须原路退回；余额支付退回余额账户；
 *     混合支付按各支付方式实付占比分摊退回，多次部分退款累计不超过实付金额。</li>
 * </ul>
 */
@FeignClient(name = "shop-pay-service", path = "/inner/pay")
public interface PayClient {

    /**
     * 创建支付单并调用第三方支付渠道下单（余额支付为内部扣减）。
     * 同一订单重复发起时按 orderNo 幂等返回原支付单。
     */
    @PostMapping("/payment")
    Result<PaymentDTO> createPayment(@Valid @RequestBody CreatePaymentCommand cmd);

    /**
     * 按支付单号查询支付单。
     */
    @GetMapping("/payment")
    Result<PaymentDTO> getByPayNo(@RequestParam("payNo") String payNo);

    /**
     * 按订单号查询当前活跃支付单（多次支付尝试时 active_slot=0 的最新一笔）；
     * 无活跃单（未发起或仅存 FAIL/CLOSED 墓碑单）时 {@code data} 为 {@code null}，不返回错误。
     * 供跨域对账兜底在存档 payNo 已终态后回查最新尝试（R4-24）。
     */
    @GetMapping("/active-payment")
    Result<PaymentDTO> getActiveByOrderNo(@RequestParam("orderNo") String orderNo);

    /**
     * 发起退款（售后退款 / 价保补差 / 清算冲正）。原路退回；混合支付按比例分别退回；
     * 以 refundNo 幂等，多次部分退款累计不得超过支付单实付金额。
     */
    @PostMapping("/refund")
    Result<RefundDTO> refund(@Valid @RequestBody CreateRefundCommand cmd);
}
