package com.shop.pay.feature.payment.controller;

import com.shop.api.pay.dto.PaymentDTO;
import com.shop.common.result.Result;
import com.shop.framework.web.LoginUser;
import com.shop.pay.feature.payment.dto.PayCreateRequest;
import com.shop.pay.feature.payment.service.PaymentService;
import com.shop.pay.support.WebIdentity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端支付接口：下单支付、查询。支付结果一律以渠道异步回调为准。
 */
@RestController
@RequestMapping("/pays")
@RequiredArgsConstructor
public class PayController {

    private final PaymentService paymentService;

    /**
     * 发起支付（普通 / 组合 / 好友代付），同一 orderNo 幂等。
     * C-3：userId 强制取网关注入的登录身份，请求体 userId 一律忽略；金额由支付域按订单应付金额反查。
     */
    @PostMapping
    public Result<PaymentDTO> create(@Valid @RequestBody PayCreateRequest request) {
        LoginUser loginUser = WebIdentity.requireUser();
        request.setUserId(loginUser.getUserId());
        return Result.success(paymentService.createPayment(request));
    }

    @GetMapping("/{payNo}")
    public Result<PaymentDTO> get(@PathVariable("payNo") String payNo) {
        return Result.success(paymentService.viewByPayNo(payNo, WebIdentity.requireUser()));
    }

    @GetMapping("/order/{orderNo}")
    public Result<PaymentDTO> getByOrder(@PathVariable("orderNo") String orderNo) {
        return Result.success(paymentService.viewByOrderNo(orderNo, WebIdentity.requireUser()));
    }
}
