package com.shop.marketing.audit;

import com.shop.framework.audit.AuditLog;
import com.shop.marketing.activity.controller.ActivityAdminController;
import com.shop.marketing.controller.PlatformAuditController;
import com.shop.marketing.coupon.controller.CouponAdminController;
import com.shop.marketing.promo.controller.PromoAdminController;
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
 * O5 防回归：marketing 全部 admin 写端点必须标注 @AuditLog，GET 端点禁止标注。
 * 新增/漏标/动错 action 常量即红。切面成功/失败两路径由 framework AuditLogAspect 自测覆盖。
 */
class AdminAuditAnnotationCoverageTest {

    /** methodName -> 期望的 action 受控常量。 */
    private static final Map<Class<?>, Map<String, String>> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put(ActivityAdminController.class, Map.of(
                "save", "MARKETING_ACTIVITY_SAVE",
                "changeStatus", "MARKETING_ACTIVITY_STATUS_CHANGE"));
        EXPECTED.put(CouponAdminController.class, Map.of(
                "save", "MARKETING_COUPON_SAVE",
                "changeStatus", "MARKETING_COUPON_STATUS_CHANGE"));
        EXPECTED.put(PromoAdminController.class, Map.of(
                "save", "MARKETING_PROMO_SAVE",
                "changeStatus", "MARKETING_PROMO_STATUS_CHANGE"));
        EXPECTED.put(PlatformAuditController.class, Map.of(
                "approve", "MARKETING_AUDIT_APPROVE",
                "reject", "MARKETING_AUDIT_REJECT"));
    }

    @Test
    void 全部admin写方法标注AuditLog且action受控_get方法不标注() {
        for (Map.Entry<Class<?>, Map<String, String>> entry : EXPECTED.entrySet()) {
            Class<?> controller = entry.getKey();
            Map<String, String> remaining = new LinkedHashMap<>(entry.getValue());
            for (Method method : controller.getDeclaredMethods()) {
                boolean write = method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class);
                AuditLog auditLog = method.getAnnotation(AuditLog.class);
                if (method.isAnnotationPresent(GetMapping.class)) {
                    assertTrue(auditLog == null,
                            controller.getSimpleName() + "." + method.getName() + " 是 GET，不应标注 @AuditLog");
                    continue;
                }
                if (!write) {
                    continue;
                }
                assertNotNull(auditLog,
                        controller.getSimpleName() + "." + method.getName() + " 写端点漏标 @AuditLog");
                assertFalse(auditLog.action().isBlank(), "action 不能为空");
                assertEquals(auditLog.action().trim(), auditLog.action(), "action 必须为无空白受控常量");
                assertEquals(remaining.remove(method.getName()), auditLog.action(),
                        controller.getSimpleName() + "." + method.getName() + " action 不匹配");
                assertFalse(auditLog.targetType().isBlank(),
                        controller.getSimpleName() + "." + method.getName() + " targetType 不能为空");
                assertFalse(auditLog.targetIdSpEL().isBlank(),
                        controller.getSimpleName() + "." + method.getName() + " targetIdSpEL 不能为空");
                assertTrue(auditLog.captureArgs(),
                        controller.getSimpleName() + "." + method.getName() + " 管理写操作应 captureArgs=true");
            }
            assertTrue(remaining.isEmpty(),
                    controller.getSimpleName() + " 期望标注的方法未找到: " + remaining.keySet());
        }
    }
}
