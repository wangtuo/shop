package com.shop.framework.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.JsonUtils;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.framework.web.trace.TraceMdcFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuditLogAspect 五例：成功、异常 rethrow、SpEL 取目标、匿名 SYSTEM、敏感字段脱敏+跨线程 MDC 不串。
 */
class AuditLogAspectTest {

    private ListAppender<ILoggingEvent> appender;
    private AdminService proxy;

    /** 测试用管理服务（真实代理走 @AuditLog 切面）。 */
    static class AdminService {
        @AuditLog(action = "MERCHANT_LEVEL_CHANGE", targetType = "MERCHANT", targetIdSpEL = "#id")
        public String changeLevel(long id, int level) {
            return "ok-" + id + "-" + level;
        }

        @AuditLog(action = "ALWAYS_FAIL", targetType = "DEMO")
        public String boom() {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "boom-msg");
        }

        @AuditLog(action = "AUDIT_SPOJO", targetType = "AUDITREQ", targetIdSpEL = "#req.id", captureArgs = true)
        public String withRequest(AuditReq req) {
            return req.id;
        }

        @AuditLog(action = "SENSITIVE", captureArgs = true)
        public void changePassword(String account, String password, String cardNo) {
        }
    }

    static class AuditReq {
        public String id;
        public String remark;

        AuditReq(String id, String remark) {
            this.id = id;
            this.remark = remark;
        }
    }

    @BeforeEach
    void setUp() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuditLogAspect.AUDIT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        AspectJProxyFactory factory = new AspectJProxyFactory(new AdminService());
        factory.addAspect(new AuditLogAspect());
        proxy = factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(AuditLogAspect.AUDIT_LOGGER_NAME)).detachAppender(appender);
        UserContext.clear();
        MDC.clear();
    }

    private JsonNode lastEvent() {
        assertEquals(1, appender.list.size(), "恰好一条审计日志");
        String json = appender.list.get(0).getFormattedMessage();
        return JsonUtils.fromJson(json, JsonNode.class);
    }

    @Test
    void successPathRecordsOperatorTargetAndTrace() {
        LoginUser user = LoginUser.builder().userId(1001L).userName("alice").userType(3).merchantId(9L).build();
        UserContext.set(user);
        MDC.put(TraceMdcFilter.MDC_TRACE_ID, "trace-xyz-1234567890");
        MDC.put(TraceMdcFilter.MDC_REQUEST_ID, "req-abc-1234567890");

        assertEquals("ok-77-2", proxy.changeLevel(77L, 2));

        JsonNode event = lastEvent();
        assertEquals("SUCCESS", event.get("result").asText());
        assertEquals("MERCHANT_LEVEL_CHANGE", event.get("action").asText());
        assertEquals("MERCHANT", event.get("targetType").asText());
        assertEquals("77", event.get("targetId").asText());
        assertEquals(1001L, event.get("userId").asLong());
        assertEquals("alice", event.get("userName").asText());
        assertEquals("3", event.get("userType").asText());
        assertEquals(9L, event.get("merchantId").asLong());
        assertEquals("trace-xyz-1234567890", event.get("traceId").asText());
        assertEquals("req-abc-1234567890", event.get("requestId").asText());
        assertTrue(event.get("costMs").asLong() >= 0L);
        assertNull(event.get("args"), "未开 captureArgs 不应记录入参");
    }

    @Test
    void failurePathLogsFailAndRethrows() {
        UserContext.set(LoginUser.builder().userId(1001L).build());
        BizException ex = assertThrows(BizException.class, () -> proxy.boom());
        assertEquals("boom-msg", ex.getMessage());

        JsonNode event = lastEvent();
        assertEquals("FAIL", event.get("result").asText());
        assertEquals(BizException.class.getName(), event.get("errorClass").asText());
        assertEquals("boom-msg", event.get("errorMessage").asText());
    }

    @Test
    void spelResolvesBeanPropertyAndCapturesArgs() {
        UserContext.set(LoginUser.builder().userId(1L).build());
        proxy.withRequest(new AuditReq("REQ-9", "hello"));

        JsonNode event = lastEvent();
        assertEquals("REQ-9", event.get("targetId").asText());
        JsonNode args = event.get("args");
        assertNotNull(args);
        assertEquals("REQ-9", args.get("req").get("id").asText());
    }

    @Test
    void anonymousInvocationRecordedAsSystem() {
        proxy.changeLevel(5L, 1);
        JsonNode event = lastEvent();
        assertEquals(0L, event.get("userId").asLong());
        assertEquals("SYSTEM", event.get("userType").asText());
    }

    @Test
    void sensitiveArgsMaskedAndMdcDoesNotLeakAcrossThreads() throws Exception {
        UserContext.set(LoginUser.builder().userId(7L).build());
        // 主线程 MDC 放入 requestId
        MDC.put(TraceMdcFilter.MDC_REQUEST_ID, "req-main-12345678");

        // 在另一线程执行切面：该线程无 MDC，审计事件不得看到主线程的 requestId
        Thread worker = new Thread(() -> proxy.changePassword("acc01", "p@ssw0rd", "6225880000001111"));
        worker.start();
        worker.join(5000);

        // 先断言脱敏（worker 线程也写同一个 appender）
        JsonNode event = lastEvent();
        JsonNode args = event.get("args");
        assertNotNull(args);
        assertEquals("acc01", args.get("account").asText());
        assertEquals("***", args.get("password").asText());
        assertEquals("***", args.get("cardNo").asText());
        assertNull(event.get("requestId"), "消费/工作线程不得串到主线程 MDC");
    }
}
