package com.shop.aftersale.aftersale.controller;

import com.shop.framework.audit.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O5 管理写端点 @AuditLog 覆盖率（fail-closed）。
 *
 * <p>反射枚举 controller 包下全部 controller 类的写映射方法（POST/PUT/PATCH/DELETE），
 * 每一个要么带 {@link AuditLog}，要么显式登记在白名单并注明原因；GET 一律不标。
 * 新增管理写端点而未补注解/未登记白名单时本测试直接失败。</p>
 */
class AnnotationCoverageTest {

    private static final String CONTROLLER_PKG = "com.shop.aftersale.aftersale.controller";

    /** 必须覆盖的管理写端点 -> 期望的注解属性（action/targetType/targetIdSpEL/captureArgs）。 */
    private static final Map<String, ExpectedAudit> REQUIRED = new HashMap<>();

    /** 显式不标 @AuditLog 的写端点：买家 C 端自助动作、商户履约自助动作。 */
    private static final Map<String, String> WHITELIST = new HashMap<>();

    record ExpectedAudit(String action, String targetType, String targetIdSpEL, boolean captureArgs) {
    }

    static {
        // 平台运营：仲裁（O5 最高危动款/裁决动作）
        REQUIRED.put("PlatformAftersaleController.arbitrate",
                new ExpectedAudit("AFTERSALE_ARBITRATE", "AFTERSALE", "#aftersaleNo", true));
        // 商户：审核同意/拒绝（同端点按 agree 分支，action 用合并常量，agree/rejectReason 入参留痕）
        REQUIRED.put("MerchantAftersaleController.audit",
                new ExpectedAudit("AFTERSALE_APPROVE_OR_REJECT", "AFTERSALE", "#aftersaleNo", true));
        // 商户：确认收货退款/拒收（accept=false 拒收结论随 captureArgs 记录）
        REQUIRED.put("MerchantAftersaleController.receive",
                new ExpectedAudit("AFTERSALE_CONFIRM_RECEIVE", "AFTERSALE", "#aftersaleNo", true));
        // 商户：举证
        REQUIRED.put("MerchantAftersaleController.evidence",
                new ExpectedAudit("AFTERSALE_SUBMIT_EVIDENCE", "AFTERSALE", "#aftersaleNo", false));

        // 商户换货/补发发货：常规履约自助动作，不在 O5 aftersale 管理审计动作集
        WHITELIST.put("MerchantAftersaleController.ship", "商户换货/补发发货，履约自助非管理审核动作");
        // 买家 C 端自助：申请/撤单/重提/寄回物流/签收/申请平台介入/买家举证/价保试算
        WHITELIST.put("AftersaleController.apply", "买家自助申请售后");
        WHITELIST.put("AftersaleController.cancel", "买家自助撤单");
        WHITELIST.put("AftersaleController.resubmit", "买家自助修改重提");
        WHITELIST.put("AftersaleController.returnLogistics", "买家自助填写寄回物流");
        WHITELIST.put("AftersaleController.exchangeConfirm", "买家自助确认签收");
        WHITELIST.put("AftersaleController.intervene", "买家自助申请平台介入");
        WHITELIST.put("AftersaleController.evidence", "买家自助举证");
        WHITELIST.put("AftersaleController.priceProtectTrial", "买家自助价保试算");
    }

    @Test
    void allControllerWriteEndpoints_areAuditedOrWhitelisted() throws Exception {
        Set<String> requiredSeen = new java.util.HashSet<>();
        for (Class<?> controller : scanControllerClasses()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                boolean write = method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class)
                        || (method.isAnnotationPresent(RequestMapping.class)
                        && hasWriteMethod(method.getAnnotation(RequestMapping.class).method()));
                boolean read = method.isAnnotationPresent(GetMapping.class)
                        || (method.isAnnotationPresent(RequestMapping.class)
                        && isGetOnly(method.getAnnotation(RequestMapping.class).method()));
                String key = controller.getSimpleName() + "." + method.getName();

                if (read) {
                    assertFalse(method.isAnnotationPresent(AuditLog.class),
                            "GET 只读端点不应标 @AuditLog: " + key);
                    continue;
                }
                if (!write) {
                    continue;
                }

                AuditLog audit = method.getAnnotation(AuditLog.class);
                if (audit != null) {
                    ExpectedAudit expected = REQUIRED.get(key);
                    assertNotNull(expected, "未登记的 @AuditLog 端点（请在本测试登记冻结属性）: " + key);
                    assertEquals(expected.action(), audit.action(), key + " action");
                    assertEquals(expected.targetType(), audit.targetType(), key + " targetType");
                    assertEquals(expected.targetIdSpEL(), audit.targetIdSpEL(), key + " targetIdSpEL");
                    assertEquals(expected.captureArgs(), audit.captureArgs(), key + " captureArgs");
                    requiredSeen.add(key);
                } else {
                    assertTrue(WHITELIST.containsKey(key),
                            "管理写端点缺少 @AuditLog 且未登记白名单: " + key
                                    + "（管理动作必须加审计；自助动作请在白名单注明原因）");
                }
            }
        }
        assertEquals(REQUIRED.keySet(), requiredSeen,
                "REQUIRED 中存在未被反射枚举到的端点（方法改名/类移动需同步本测试）");
        // 白名单与必标集合互斥
        for (String key : WHITELIST.keySet()) {
            assertFalse(REQUIRED.containsKey(key), "白名单与必标集合冲突: " + key);
        }
    }

    @Test
    void whitelistReasons_areNonBlank() {
        WHITELIST.forEach((key, reason) ->
                assertNotNull(reason, key));
        for (String reason : WHITELIST.values()) {
            assertFalse(reason.isBlank());
        }
    }

    private boolean hasWriteMethod(org.springframework.web.bind.annotation.RequestMethod[] methods) {
        for (var m : methods) {
            if (m != org.springframework.web.bind.annotation.RequestMethod.GET) {
                return true;
            }
        }
        return false;
    }

    private boolean isGetOnly(org.springframework.web.bind.annotation.RequestMethod[] methods) {
        return methods.length == 1
                && methods[0] == org.springframework.web.bind.annotation.RequestMethod.GET;
    }

    /**
     * 扫描主代码 controller 包下全部 *Controller 类（fail-closed：新增 controller 自动纳入）。
     * 以平台 controller 自身 CodeSource（target/classes 根）为锚再进入包目录——
     * 不能用 getResource(".")，同包测试类会让 classloader 先命中 target/test-classes。
     */
    private List<Class<?>> scanControllerClasses() throws Exception {
        File classRoot = new File(PlatformAftersaleController.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        File dir = new File(classRoot, CONTROLLER_PKG.replace('.', '/'));
        File[] files = dir.listFiles((d, name) -> name.endsWith("Controller.class"));
        assertNotNull(files);
        List<Class<?>> classes = new ArrayList<>();
        for (File f : files) {
            String simple = f.getName().substring(0, f.getName().length() - ".class".length());
            classes.add(Class.forName(CONTROLLER_PKG + "." + simple));
        }
        // 四个既有 controller（平台/商户/C 端/内部）
        assertTrue(classes.size() >= 4, "controller 数量异常: " + classes.size());
        return classes;
    }
}
