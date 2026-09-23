package com.shop.pay.audit;

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
 * O5 审计覆盖率白名单（pay）：反射枚举本模块全部 *Controller 写方法。
 * 管理端 /platform/recon、平台退款重试、inner Feign 退款发起必须标注；
 * 渠道回调 /notify/**（外部系统动作）、inner 支付建单、C 端支付、GET 不得标注。
 */
class AnnotationCoverageTest {

    private static final String BASE_PACKAGE = "com.shop.pay";

    /** 必须标注的写端点白名单：类名 -> 方法名 -> 冻结 action。 */
    private static final Map<String, Map<String, String>> WHITELIST = Map.of(
            "ReconcileController", Map.of(
                    "run", "RECON_SETTLE",
                    "handle", "RECON_DIFF_HANDLE",
                    "retry", "RECON_RETRY"),
            "RefundController", Map.of("retry", "REFUND_RETRY"),
            "PayInnerController", Map.of("refund", "REFUND_INITIATE"));

    @Test
    void 管理写端点_AuditLog全覆盖且动作常量冻结_回调不标() throws Exception {
        List<Class<?>> controllers = scanControllers();
        assertTrue(controllers.size() >= 5, "控制器扫描数异常: " + controllers.size());
        Map<String, Map<String, String>> remaining = deepCopy(WHITELIST);

        for (Class<?> controller : controllers) {
            String base = basePath(controller);
            for (Method method : controller.getDeclaredMethods()) {
                String writePath = writeMappingPath(method);
                if (writePath == null) {
                    assertFalse(method.isAnnotationPresent(AuditLog.class),
                            controller.getSimpleName() + "." + method.getName() + " 非写方法不应标注 @AuditLog");
                    continue;
                }
                String fullPath = base + writePath;
                Map<String, String> expected = remaining.get(controller.getSimpleName());
                String expectedAction = expected == null ? null : expected.get(method.getName());
                boolean isAdminPath = fullPath.contains("/admin") || fullPath.contains("/platform/");
                boolean isNotifyPath = fullPath.contains("/notify");

                if (expectedAction != null) {
                    AuditLog auditLog = method.getAnnotation(AuditLog.class);
                    assertNotNull(auditLog, "漏标 @AuditLog: " + controller.getSimpleName() + "." + method.getName());
                    assertEquals(expectedAction, auditLog.action(), "action 常量被改动: " + method.getName());
                    assertFalse(auditLog.targetType().isBlank(),
                            "targetType 不能为空: " + method.getName());
                    assertTrue(auditLog.captureArgs(),
                            "动款/差错类端点必须 captureArgs=true: " + method.getName());
                    expected.remove(method.getName());
                } else if (isAdminPath) {
                    assertTrue(method.isAnnotationPresent(AuditLog.class),
                            "未登记白名单的管理写端点（漏标即红）: "
                                    + controller.getSimpleName() + "." + method.getName() + " " + fullPath);
                } else {
                    // /notify 渠道回调、inner 支付建单、C 端支付等非人工管理动作一律不标
                    assertFalse(method.isAnnotationPresent(AuditLog.class),
                            "非管理端点不应标注 @AuditLog: "
                                    + controller.getSimpleName() + "." + method.getName()
                                    + (isNotifyPath ? "（渠道回调为外部系统动作）" : ""));
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
