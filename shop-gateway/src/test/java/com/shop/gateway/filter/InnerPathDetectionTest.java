package com.shop.gateway.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径归一化与内网段识别必须覆盖常见网关绕过写法（SECURITY_REVIEW C-1/H-4）。
 */
class InnerPathDetectionTest {

    @Test
    void canonicalizes_plain_paths() {
        assertEquals("/api/user/auth/login",
                JwtAuthGlobalFilter.canonicalPath("/api/user/auth/login"));
    }

    @Test
    void canonicalizes_encoded_traversal_and_matrix() {
        assertEquals("/api/user/orders/x",
                JwtAuthGlobalFilter.canonicalPath("/api/user/auth/../orders/x"));
        assertEquals("/api/user/orders/x",
                JwtAuthGlobalFilter.canonicalPath("/api/user/auth/%2e%2e/orders/x"));
        assertEquals("/api/user/orders/x",
                JwtAuthGlobalFilter.canonicalPath("/api/user/auth/..;/orders/x"));
        assertEquals("/api/inner/marketing/lock",
                JwtAuthGlobalFilter.canonicalPath("/api/marketing/promotions/../../inner/marketing/lock"));
        assertEquals("/api/inner/x",
                JwtAuthGlobalFilter.canonicalPath("/api/user/..%2finner/x"));
        assertEquals("/api/user/inner/x",
                JwtAuthGlobalFilter.canonicalPath("/api/user/inner;/x"));
        assertEquals("/api/user/inner/x",
                JwtAuthGlobalFilter.canonicalPath("/api/user/inner\\x"));
        // 双重 URL 编码
        assertEquals("/api/inner/x",
                JwtAuthGlobalFilter.canonicalPath("/api/user/%252e%252e/inner/x"));
    }

    @Test
    void blocks_plain_inner_paths() {
        assertTrue(JwtAuthGlobalFilter.containsInnerSegment(
                JwtAuthGlobalFilter.canonicalPath("/api/user/inner/accounts/grant")));
        assertTrue(JwtAuthGlobalFilter.containsInnerSegment(
                JwtAuthGlobalFilter.canonicalPath("/api/order/inner/orders/123")));
    }

    @Test
    void blocks_encoded_and_traversal_variants() {
        for (String p : new String[]{
                "/api/user/%69nner/x",
                "/api/user/%69%6e%6e%65%72/x",
                "/api/user/%2569nner/x",
                "/api/user/inner;/x",
                "/api/user/..;/inner/x",
                "/api/user/foo/../inner/x",
                "/api/user/foo/..%2finner/x",
                "/api/user/inner\\x",
                "/api/user/%49nner/x"
        }) {
            String canon = JwtAuthGlobalFilter.canonicalPath(p);
            assertTrue(JwtAuthGlobalFilter.containsInnerSegment(canon), "should block: " + p + " -> " + canon);
        }
    }

    @Test
    void keeps_normal_public_paths() {
        assertFalse(JwtAuthGlobalFilter.containsInnerSegment(
                JwtAuthGlobalFilter.canonicalPath("/api/user/auth/login")));
        assertFalse(JwtAuthGlobalFilter.containsInnerSegment(
                JwtAuthGlobalFilter.canonicalPath("/api/product/skus/123")));
        assertFalse(JwtAuthGlobalFilter.containsInnerSegment(
                JwtAuthGlobalFilter.canonicalPath("/actuator/health")));
        // 业务路径中恰好含 inner 子串但不是独立段，不得误伤
        assertFalse(JwtAuthGlobalFilter.containsInnerSegment(
                JwtAuthGlobalFilter.canonicalPath("/api/product/skus/inner-demo")));
    }
}
