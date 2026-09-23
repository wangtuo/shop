package com.shop.framework.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox 表结构启动自检（P3-3 / C15）。新环境漏跑 sql/common/V3/V5/V6 时尽早暴露，
 * 不引入 Flyway：以一次 {@code SELECT 1 FROM t_mq_outbox LIMIT 1} 探针代替迁移框架。
 */
@Data
@ConfigurationProperties(prefix = "shop.outbox.schema-check")
public class OutboxSchemaCheckProperties {

    /** 是否启用启动自检（默认 true）。 */
    private boolean enabled = true;

    /**
     * 失败处置模式：
     * <ul>
     *   <li>{@link Mode#AUTO}（默认）：prod profile → FAILFAST，其余 → LOG；</li>
     *   <li>{@link Mode#FAILFAST}：应用启动失败，错误信息指引执行 sql/common/V3/V5/V6；</li>
     *   <li>{@link Mode#LOG}：仅 WARN，应用照常启动（本地/开发）。</li>
     * </ul>
     */
    private Mode mode = Mode.AUTO;

    public enum Mode {
        AUTO, FAILFAST, LOG
    }
}
