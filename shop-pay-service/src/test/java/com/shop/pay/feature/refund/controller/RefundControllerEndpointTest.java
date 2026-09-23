package com.shop.pay.feature.refund.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * C-3 收口验证：对外 RefundController 不再暴露 C 端退款创建入口，仅保留归属查询与平台重试。
 */
class RefundControllerEndpointTest {

    @Test
    void 对外退款创建入口已移除() {
        for (Method m : RefundController.class.getDeclaredMethods()) {
            // 不存在任何无路径参数的 POST 映射（即 POST /refunds 创建入口）
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (post != null) {
                assertEquals(1, post.value().length, "仅允许带子路径的 POST（重试），发现裸 POST: " + m.getName());
                assertEquals("/{refundNo}/retry", post.value()[0]);
            }
        }
    }

    @Test
    void 保留商户归属查询与平台重试() throws Exception {
        Method get = RefundController.class.getDeclaredMethod("get", String.class);
        Method retry = RefundController.class.getDeclaredMethod("retry", String.class);
        assertNotNull(get.getAnnotation(GetMapping.class));
        assertNotNull(retry.getAnnotation(PostMapping.class));
        assertNull(get.getAnnotation(PostMapping.class));
    }
}
