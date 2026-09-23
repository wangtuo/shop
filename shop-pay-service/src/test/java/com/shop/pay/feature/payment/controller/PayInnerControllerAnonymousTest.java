package com.shop.pay.feature.payment.controller;

import com.shop.framework.web.Anonymous;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4-23 回归：内部控制器必须类级标注 {@link Anonymous}。
 *
 * <p>拼团失败退款（GroupbuyOrderFlowServiceImpl 经 MQ 消费线程调用
 * {@code PayClient.refund}）与结算链路在无用户上下文的线程内访问
 * {@code /inner/pay/**}；缺 {@code @Anonymous} 时 AuthInterceptor 一律 401，
 * 退款无法发起且失败计入按 client 共享的熔断器。纵深防御不降级：
 * InternalTokenInterceptor 仍对 /inner/** 强制校验 X-Internal-Token。</p>
 */
class PayInnerControllerAnonymousTest {

    @Test
    void classLevelAnonymous_presentForMqAndSchedulerCallers() {
        assertTrue(PayInnerController.class.isAnnotationPresent(Anonymous.class),
                "PayInnerController 必须类级 @Anonymous：MQ/调度线程经 Feign 调用 /inner/pay/** 时无 X-User-Id（R4-23）");
    }
}
