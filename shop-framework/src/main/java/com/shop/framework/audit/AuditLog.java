package com.shop.framework.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理后台操作审计注解（O5/C56）。标注于 Controller/Service 管理端点方法，
 * 由 {@link AuditLogAspect} 以 @Around 记录成功/失败两条路径，输出结构化 JSON 行日志
 * （logger 名固定 {@code AUDIT}，便于采集侧独立定向）。
 *
 * <p>本期只落日志不落库（C59 二阶段）；注解参数保持稳定，落库时无需改动标注点。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** 操作动作，受控大写常量，如 MERCHANT_LEVEL_CHANGE / AFTERSALE_ARBITRATE。 */
    String action();

    /** 目标对象类型，如 MERCHANT / ACTIVITY / REFUND。 */
    String targetType() default "";

    /**
     * 目标对象 ID 的 SpEL 表达式（方法参数为根变量，如 {@code "#id"}、{@code "#req.spuId"}）；
     * 解析失败/为空时输出 null，绝不因审计让业务方法失败。
     */
    String targetIdSpEL() default "";

    /** 是否记录入参摘要（动款/权限类建议开启）；密码/卡号/证件号等敏感字段自动脱敏。 */
    boolean captureArgs() default false;
}
