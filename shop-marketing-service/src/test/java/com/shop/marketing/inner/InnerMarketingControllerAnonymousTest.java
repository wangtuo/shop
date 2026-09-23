package com.shop.marketing.inner;

import com.shop.framework.web.Anonymous;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4-23 回归：内部控制器必须类级标注 {@link Anonymous}。
 *
 * <p>下单超时取消/拼团失败等路径在 MQ 消费或调度线程内经 Feign 调用
 * {@code /inner/marketing/**}（如 release 释放券与预占），这些线程不携带
 * X-User-Id；缺 {@code @Anonymous} 时 AuthInterceptor 一律 401，营销资源永不
 * 释放且失败计入按 client 共享的熔断器，毒化前台下单链路。纵深防御不降级：
 * InternalTokenInterceptor 仍对 /inner/** 强制校验 X-Internal-Token。</p>
 */
class InnerMarketingControllerAnonymousTest {

    @Test
    void classLevelAnonymous_presentForMqAndSchedulerCallers() {
        assertTrue(InnerMarketingController.class.isAnnotationPresent(Anonymous.class),
                "InnerMarketingController 必须类级 @Anonymous：MQ/调度线程经 Feign 调用 /inner/marketing/** 时无 X-User-Id（R4-23）");
    }
}
