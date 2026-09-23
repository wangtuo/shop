package com.shop.framework.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口幂等注解：基于 Redis SET NX EX，key 支持 SpEL（#参数名）。
 * 典型场景：提交订单、支付回调、退款回调。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** key 前缀，建议 域:动作 */
    String prefix();

    /** SpEL 表达式，如 "#request.orderNo" 或 "#userId + ':' + #skuId" */
    String key();

    /** 幂等窗口秒数，默认 24 小时 */
    long ttlSeconds() default 24 * 3600L;

    /** 命中幂等时的提示 */
    String message() default "请求正在处理中，请勿重复提交";
}
