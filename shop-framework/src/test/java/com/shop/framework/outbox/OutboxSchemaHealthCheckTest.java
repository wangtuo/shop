package com.shop.framework.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** P3-3：outbox schema 自检三态——通过 / failfast 拒绝启动（含 V3/V5/V6 指引）/ log 仅告警。 */
class OutboxSchemaHealthCheckTest {

    private OutboxSchemaCheckProperties props(OutboxSchemaCheckProperties.Mode mode) {
        OutboxSchemaCheckProperties p = new OutboxSchemaCheckProperties();
        p.setMode(mode);
        return p;
    }

    private BadSqlGrammarException tableMissing() {
        // BadSqlGrammarException 构造需要 (task, sql, SQLException)
        return new BadSqlGrammarException("SELECT 1", "SELECT 1 FROM t_mq_outbox LIMIT 1",
                new java.sql.SQLException("Table 'shop_x.t_mq_outbox' doesn't exist"));
    }

    @Test
    void 探针通过时正常启动() {
        var jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var check = new OutboxSchemaHealthCheck(jdbcTemplate,
                props(OutboxSchemaCheckProperties.Mode.FAILFAST), new MockEnvironment());
        assertDoesNotThrow(() -> check.run(null));
    }

    @Test
    void failfast模式表缺失_拒绝启动且指引含V3V5V6() {
        var jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        doThrow(tableMissing()).when(jdbcTemplate).execute("SELECT 1 FROM t_mq_outbox LIMIT 1");
        var check = new OutboxSchemaHealthCheck(jdbcTemplate,
                props(OutboxSchemaCheckProperties.Mode.FAILFAST), new MockEnvironment());
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check.run(null));
        assertTrue(ex.getMessage().contains("V3"));
        assertTrue(ex.getMessage().contains("V5"));
        assertTrue(ex.getMessage().contains("V6"));
    }

    @Test
    void log模式表缺失_仅告警不阻断() {
        var jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        doThrow(tableMissing()).when(jdbcTemplate).execute("SELECT 1 FROM t_mq_outbox LIMIT 1");
        var check = new OutboxSchemaHealthCheck(jdbcTemplate,
                props(OutboxSchemaCheckProperties.Mode.LOG), new MockEnvironment());
        assertDoesNotThrow(() -> check.run(null));
    }

    @Test
    void auto模式prod解析为failfast_dev解析为log() {
        var jdbcTemplateFail = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        doThrow(tableMissing()).when(jdbcTemplateFail).execute("SELECT 1 FROM t_mq_outbox LIMIT 1");

        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        var prodCheck = new OutboxSchemaHealthCheck(jdbcTemplateFail,
                props(OutboxSchemaCheckProperties.Mode.AUTO), prod);
        assertThrows(IllegalStateException.class, () -> prodCheck.run(null));

        var jdbcTemplateDev = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        doThrow(tableMissing()).when(jdbcTemplateDev).execute("SELECT 1 FROM t_mq_outbox LIMIT 1");
        var devCheck = new OutboxSchemaHealthCheck(jdbcTemplateDev,
                props(OutboxSchemaCheckProperties.Mode.AUTO), new MockEnvironment());
        assertDoesNotThrow(() -> devCheck.run(null));
    }
}
