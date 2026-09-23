package com.shop.framework.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Outbox schema 启动自检（P3-3 / C15）：所有业务库必须已执行 sql/common/V3、V5、V6
 * （V3 建 t_mq_outbox、V5 同构守卫、V6 outbox UK）。漏跑时 outbox 投递会在首轮即全部
 * 失败并静默挂起，故在启动期直接探测：
 *
 * <pre>SELECT 1 FROM t_mq_outbox LIMIT 1</pre>
 *
 * 不查 information_schema（规避账号权限差异）；表存在但为空同样视为通过。
 * prod 默认 failfast（拒绝启动 + 明确指引），dev/local 默认仅 WARN。
 */
@Component
@EnableConfigurationProperties(OutboxSchemaCheckProperties.class)
public class OutboxSchemaHealthCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OutboxSchemaHealthCheck.class);

    static final String GUIDE = "t_mq_outbox 不可用：请按顺序执行 sql/common/V3__outbox.sql、"
            + "V5（同构守卫）、V6__outbox_biz_key_uk.sql 后再启动（P3-3 启动自检）";

    private final JdbcTemplate jdbcTemplate;
    private final OutboxSchemaCheckProperties properties;
    private final Environment environment;

    public OutboxSchemaHealthCheck(JdbcTemplate jdbcTemplate,
                                   OutboxSchemaCheckProperties properties,
                                   Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        boolean failfast = resolveFailfast();
        try {
            jdbcTemplate.execute("SELECT 1 FROM t_mq_outbox LIMIT 1");
            log.info("outbox schema 自检通过（t_mq_outbox 可访问）");
        } catch (Exception e) {
            if (failfast) {
                throw new IllegalStateException(GUIDE + "；底层错误：" + e.getMessage(), e);
            }
            log.warn("{}（mode=log，本次仅告警不阻断启动；底层错误：{}）", GUIDE, e.toString());
        }
    }

    private boolean resolveFailfast() {
        OutboxSchemaCheckProperties.Mode mode = properties.getMode();
        if (mode == OutboxSchemaCheckProperties.Mode.FAILFAST) {
            return true;
        }
        if (mode == OutboxSchemaCheckProperties.Mode.LOG) {
            return false;
        }
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }
}
