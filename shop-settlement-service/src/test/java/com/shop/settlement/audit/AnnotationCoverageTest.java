package com.shop.settlement.audit;

import com.shop.framework.audit.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O5 审计覆盖率白名单（settlement）：反射枚举本模块全部 *Controller 的写方法
 * （POST/PUT/PATCH/DELETE），断言管理端/受控内部写端点 100% 标注 @AuditLog；
 * GET、商户/C 端自助写端点、未列入白名单的 inner 端点不得标注。新增管理端点漏标即红。
 */
class AnnotationCoverageTest {

    private static final String BASE_PACKAGE = "com.shop.settlement";

    /** 必须标注的写端点白名单：类名 -> 方法名 -> 冻结 action。 */
    private static final Map<String, Map<String, String>> WHITELIST = Map.of(
            "MerchantDepositController", Map.of("pay", "SETTLE_DEPOSIT_PAY"),
            "AdminController", Map.of(
                    "onboard", "MERCHANT_CREATE",
                    "updateLevel", "MERCHANT_LEVEL_CHANGE",
                    "resign", "MERCHANT_RESIGN",
                    "depositFine", "SETTLE_DEPOSIT_FINE",
                    "markWithdrawFailed", "WITHDRAW_MARK_FAILED",
                    "manualSettle", "SETTLE_MANUAL_RUN"));

    @Test
    void 管理写端点_AuditLog全覆盖且动作常量冻结() throws Exception {
        List<Class<?>> controllers = scanControllers();
        assertTrue(controllers.size() >= 6, "控制器扫描数异常: " + controllers.size());
        Map<String, Map<String, String>> remaining = deepCopy(WHITELIST);

        for (Class<?> controller : controllers) {
            String base = basePath(controller);
            for (Method method : controller.getDeclaredMethods()) {
                String writePath = writeMappingPath(method);
                if (writePath == null) {
                    // GET/无 mapping：绝不标 @AuditLog
                    assertFalse(method.isAnnotationPresent(AuditLog.class),
                            controller.getSimpleName() + "." + method.getName() + " 非写方法不应标注 @AuditLog");
                    continue;
                }
                String fullPath = base + writePath;
                Map<String, String> expected = remaining.get(controller.getSimpleName());
                String expectedAction = expected == null ? null : expected.get(method.getName());
                boolean isAdminPath = fullPath.contains("/admin") || fullPath.contains("/platform/");

                if (expectedAction != null) {
                    AuditLog auditLog = method.getAnnotation(AuditLog.class);
                    assertNotNull(auditLog, "漏标 @AuditLog: " + controller.getSimpleName() + "." + method.getName());
                    assertEquals(expectedAction, auditLog.action(), "action 常量被改动: " + method.getName());
                    assertFalse(auditLog.targetType().isBlank(),
                            "targetType 不能为空: " + method.getName());
                    assertTrue(auditLog.captureArgs(),
                            "动款/权限类端点必须 captureArgs=true: " + method.getName());
                    expected.remove(method.getName());
                } else if (isAdminPath) {
                    // 兜底：任何 /admin、/platform 写方法都必须在白名单+注解内（防新增端点漏标）
                    assertTrue(method.isAnnotationPresent(AuditLog.class),
                            "未登记白名单的管理写端点（漏标即红）: "
                                    + controller.getSimpleName() + "." + method.getName() + " " + fullPath);
                } else {
                    // 商户/C 端自助写端点（如商户提现申请）、未登记 inner 端点不得滥标
                    assertFalse(method.isAnnotationPresent(AuditLog.class),
                            "非管理端点不应标注 @AuditLog: "
                                    + controller.getSimpleName() + "." + method.getName());
                }
            }
        }

        remaining.forEach((cls, methods) -> methods.keySet().forEach(m ->
                org.junit.jupiter.api.Assertions.fail("白名单端点不存在或未扫描到: " + cls + "#" + m)));
    }

    // ------------------------------------------------------------------

    private static Map<String, Map<String, String>> deepCopy(Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(k, new LinkedHashMap<>(v)));
        return copy;
    }

    private static String basePath(Class<?> controller) {
        RequestMapping rm = controller.getAnnotation(RequestMapping.class);
        if (rm == null) {
            return "";
        }
        String[] values = rm.value().length > 0 ? rm.value() : rm.path();
        return values.length > 0 ? values[0] : "";
    }

    /** 返回写映射路径（非写方法返回 null）。 */
    private static String writeMappingPath(Method method) {
        for (Class<? extends Annotation> type : List.of(
                PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class)) {
            Annotation a = method.getAnnotation(type);
            if (a != null) {
                try {
                    String[] values = (String[]) type.getMethod("value").invoke(a);
                    return values.length > 0 ? values[0] : "";
                } catch (ReflectiveOperationException e) {
                    return "";
                }
            }
        }
        return null;
    }

    private static List<Class<?>> scanControllers() throws Exception {
        String packagePath = BASE_PACKAGE.replace('.', '/');
        List<Class<?>> classes = new ArrayList<>();
        for (URL url : Collections.list(
                Thread.currentThread().getContextClassLoader().getResources(packagePath))) {
            if (!"file".equals(url.getProtocol())) {
                continue;
            }
            Path root = Path.of(url.toURI());
            try (var stream = Files.walk(root)) {
                stream.filter(p -> p.getFileName().toString().endsWith("Controller.class"))
                        .forEach(p -> {
                            String relative = root.relativize(p).toString()
                                    .replace(File.separatorChar, '/').replace('/', '.');
                            String className = BASE_PACKAGE + "."
                                    + relative.substring(0, relative.length() - ".class".length());
                            try {
                                classes.add(Class.forName(className));
                            } catch (Throwable ignored) {
                                // 仅统计可加载的 controller
                            }
                        });
            }
        }
        return classes;
    }
}
