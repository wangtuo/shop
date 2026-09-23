package com.shop.framework.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.util.JsonUtils;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.framework.web.trace.TraceMdcFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link AuditLog} 切面（O5/C56）：成功/异常两条路径都输出 AUDIT logger JSON 行，
 * 异常路径记录后原样 rethrow，不改变业务语义与事务回滚。
 *
 * <p>优先级 {@code HIGHEST_PRECEDENCE + 50}：贴近最外层以统计真实耗时；
 * 切点用静态注解全限定名 + 反射取注解（不使用 @annotation 形参绑定），
 * 与 {@code RateLimitAspect} 注释中记录的 ExposeInvocationInterceptor 排序陷阱同理，
 * 与 Idempotent/RateLimit 任意 order 共存。</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AuditLogAspect {

    static final String AUDIT_LOGGER_NAME = "AUDIT";
    static final String RESULT_SUCCESS = "SUCCESS";
    static final String RESULT_FAIL = "FAIL";
    static final String SYSTEM = "SYSTEM";

    private static final Logger AUDIT = LoggerFactory.getLogger(AUDIT_LOGGER_NAME);
    private static final String MASK = "***";
    /** 命中即整体脱敏的字段名（小写包含匹配）：密码/卡号/证件/CVV/密钥/令牌。 */
    private static final Pattern SENSITIVE = Pattern.compile(
            ".*(password|passwd|cardno|bankcard|idcard|cvv|secret|token).*");

    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
    private final ObjectMapper objectMapper = JsonUtils.mapper();

    @Around("@annotation(com.shop.framework.audit.AuditLog)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = pjp.getTarget() != null ? pjp.getTarget().getClass() : null;
        Method mostSpecific = targetClass != null
                ? AopUtils.getMostSpecificMethod(method, targetClass) : method;
        AuditLog auditLog = AnnotatedElementUtils.findMergedAnnotation(mostSpecific, AuditLog.class);
        if (auditLog == null) {
            return pjp.proceed();
        }

        long start = System.currentTimeMillis();
        AuditEvent event = baseEvent(auditLog, method, pjp.getArgs());
        try {
            Object result = pjp.proceed();
            event.setResult(RESULT_SUCCESS);
            event.setCostMs(System.currentTimeMillis() - start);
            write(event);
            return result;
        } catch (Throwable t) {
            event.setResult(RESULT_FAIL);
            event.setErrorClass(t.getClass().getName());
            String message = t.getMessage();
            event.setErrorMessage(message == null ? null : message.substring(0, Math.min(message.length(), 500)));
            event.setCostMs(System.currentTimeMillis() - start);
            write(event);
            throw t;
        }
    }

    private AuditEvent baseEvent(AuditLog auditLog, Method method, Object[] args) {
        AuditEvent event = new AuditEvent();
        event.setTs(System.currentTimeMillis());
        event.setTraceId(MDC.get(TraceMdcFilter.MDC_TRACE_ID));
        event.setRequestId(MDC.get(TraceMdcFilter.MDC_REQUEST_ID));

        LoginUser user = UserContext.getOrNull();
        if (user != null && user.getUserId() != null) {
            event.setUserId(user.getUserId());
            event.setUserName(user.getUserName());
            event.setUserType(user.getUserType() == null ? null : String.valueOf(user.getUserType()));
            event.setMerchantId(user.getMerchantId());
        } else {
            event.setUserId(0L);
            event.setUserType(SYSTEM);
        }

        event.setAction(auditLog.action());
        event.setTargetType(blankToNull(auditLog.targetType()));
        event.setTargetId(resolveTargetId(auditLog, method, args));
        event.setClientIp(clientIp());
        if (auditLog.captureArgs()) {
            event.setArgs(sanitizeArgs(method, args));
        }
        return event;
    }

    /** SpEL 取目标 ID；任何异常（参数名丢失/求值失败）一律降级 null，不阻断业务。 */
    private String resolveTargetId(AuditLog auditLog, Method method, Object[] args) {
        String expr = auditLog.targetIdSpEL();
        if (expr == null || expr.isBlank()) {
            return null;
        }
        try {
            StandardEvaluationContext context = new StandardEvaluationContext();
            String[] paramNames = discoverer.getParameterNames(method);
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            Object value = parser.parseExpression(expr).getValue(context);
            if (value == null) {
                return null;
            }
            String s = value.toString();
            return s.isBlank() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 入参摘要：转 Jackson 通用 Map/标量结构后递归脱敏；无法转换的参数降级为
     * 类名占位，避免审计日志序列化失败。
     */
    private Object sanitizeArgs(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        String[] paramNames = discoverer.getParameterNames(method);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = paramNames != null && i < paramNames.length ? paramNames[i] : "arg" + i;
            Object raw = args[i];
            if (raw == null) {
                snapshot.put(name, null);
                continue;
            }
            if (SENSITIVE.matcher(name.toLowerCase()).matches()) {
                snapshot.put(name, MASK);
                continue;
            }
            try {
                Object converted = objectMapper.convertValue(raw, Object.class);
                snapshot.put(name, sanitize(converted, name));
            } catch (IllegalArgumentException e) {
                snapshot.put(name, "<" + raw.getClass().getSimpleName() + ">");
            }
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private Object sanitize(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (fieldName != null && SENSITIVE.matcher(fieldName.toLowerCase()).matches()) {
            return MASK;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                out.put(key, sanitize(entry.getValue(), key));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> sanitize(v, null)).toList();
        }
        return value;
    }

    static String clientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real.trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private void write(AuditEvent event) {
        try {
            AUDIT.info(JsonUtils.toJson(event));
        } catch (Exception e) {
            // 审计失败永不影响业务；兜底简单行
            AUDIT.warn("audit serialize failed action={} result={}", event.getAction(), event.getResult());
        }
    }
}
