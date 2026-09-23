package com.shop.pay.feature.payment.controller;

import com.shop.framework.idempotent.Idempotent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P2-2 复核：渠道回调端点不得使用切面 {@code @Idempotent}。
 *
 * <p>渠道对账重发同一通知（甚至更换 notifyId）属正常行为，必须由服务层
 * t_pay_notify_log 幂等并返回成功；切面会直接返回 REPEAT_SUBMIT，渠道拿不到 ACK
 * 会持续重试并刷告警。
 */
class ChannelNotifyControllerIdempotentTest {

    @Test
    void 渠道回调端点不允许挂Idempotent切面() throws Exception {
        Method method = ChannelNotifyController.class.getDeclaredMethod(
                "payNotify", String.class,
                Class.forName("com.shop.pay.feature.payment.dto.PayNotifyRequest"));
        assertNull(method.getAnnotation(Idempotent.class),
                "渠道回调重复通知必须走 t_pay_notify_log 服务层幂等并返回成功，不得由切面 REPEAT_SUBMIT 拒绝");
    }
}
