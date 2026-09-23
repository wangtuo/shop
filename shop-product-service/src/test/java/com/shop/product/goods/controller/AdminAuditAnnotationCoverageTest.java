package com.shop.product.goods.controller;

import com.shop.framework.audit.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O5 防回归：AdminGoodsController 全部平台写端点（审核/违规下架/后续状态变更）
 * 必须标注 @AuditLog，GET 端点禁止标注。切面两路径由 framework 自测覆盖。
 */
class AdminAuditAnnotationCoverageTest {

    /** methodName -> 期望的 action 受控常量。 */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("audit", "PRODUCT_AUDIT");
        EXPECTED.put("violation", "PRODUCT_VIOLATION");
    }

    @Test
    void 全部admin写方法标注AuditLog且action受控_get方法不标注() {
        Map<String, String> remaining = new LinkedHashMap<>(EXPECTED);
        for (Method method : AdminGoodsController.class.getDeclaredMethods()) {
            boolean write = method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class);
            AuditLog auditLog = method.getAnnotation(AuditLog.class);
            if (method.isAnnotationPresent(GetMapping.class)) {
                assertTrue(auditLog == null, method.getName() + " 是 GET，不应标注 @AuditLog");
                continue;
            }
            if (!write) {
                continue;
            }
            assertNotNull(auditLog, method.getName() + " 写端点漏标 @AuditLog");
            assertFalse(auditLog.action().isBlank(), "action 不能为空");
            assertEquals(remaining.remove(method.getName()), auditLog.action(),
                    method.getName() + " action 不匹配");
            assertEquals("SPU", auditLog.targetType(), method.getName() + " targetType 必须为 SPU");
            assertEquals("#spuId", auditLog.targetIdSpEL(),
                    method.getName() + " targetIdSpEL 必须为 #spuId");
            assertTrue(auditLog.captureArgs(), method.getName() + " 审核类操作应 captureArgs=true");
        }
        assertTrue(remaining.isEmpty(), "期望标注的方法未找到: " + remaining.keySet());
    }
}
