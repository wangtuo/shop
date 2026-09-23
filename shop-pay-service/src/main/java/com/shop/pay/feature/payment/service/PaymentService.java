package com.shop.pay.feature.payment.service;

import com.shop.api.pay.dto.CreatePaymentCommand;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.framework.web.LoginUser;
import com.shop.pay.feature.payment.dto.ChannelNotifyParams;
import com.shop.pay.feature.payment.dto.PayCreateRequest;

/**
 * 支付单领域服务（design 6.2/6.3）。
 */
public interface PaymentService {

    /** 内部 Feign：创建支付单（单手段），按 orderNo 幂等。 */
    PaymentDTO createPayment(CreatePaymentCommand command);

    /** C 端：创建支付单（普通/组合/好友代付）。userId 以网关注入的登录身份为准，金额以订单应付为准。 */
    PaymentDTO createPayment(PayCreateRequest request);

    /** 内部查询：不带归属校验（仅 /inner Feign 与域内作业使用）。 */
    PaymentDTO getByPayNo(String payNo);

    /** 内部查询：不带归属校验（仅 /inner Feign 与域内作业使用）。 */
    PaymentDTO getByOrderNo(String orderNo);

    /**
     * 内部查询订单当前活跃支付单（active_slot=0），不存在（无支付单或仅存 FAIL/CLOSED 墓碑单）
     * 返回 {@code null}——区别于抛 NOT_FOUND 的 {@link #getByOrderNo}，供跨域对账兜底按
     * orderNo 回查最新一笔尝试（R4-24 加固：陈旧 payNo 回查）。
     */
    PaymentDTO findActiveByOrderNo(String orderNo);

    /** C 端查询：校验支付单归属（买家本人或平台运营），越权抛 FORBIDDEN。 */
    PaymentDTO viewByPayNo(String payNo, LoginUser viewer);

    /** C 端查询：按订单号校验支付单归属（买家本人或平台运营），越权抛 FORBIDDEN。 */
    PaymentDTO viewByOrderNo(String orderNo, LoginUser viewer);

    /** 渠道异步回调处理：验签 → 幂等 → 金额/状态校验 → 落单 → 发事件。 */
    PaymentDTO handleNotify(ChannelNotifyParams params);

    /** 主动查询渠道并推进状态（补偿双保险）。 */
    PaymentDTO activeQuery(String payNo);

    /** 超时单扫描推进/关单，返回处理条数。 */
    int scanTimeout(int limit);
}
