package com.shop.framework.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 5.x Proxy 接入配置。enabled=false 时生产者只记日志（本地开发/单元测试）。
 */
@Data
@ConfigurationProperties(prefix = "shop.mq")
public class MqProperties {

    private boolean enabled = true;

    /** Proxy 地址，如 127.0.0.1:8081 */
    private String endpoints = "127.0.0.1:8081";

    /**
     * @deprecated 死键已下线（Z3 / C17）：RocketMQ 5.x gRPC 客户端
     * {@code PushConsumerBuilder}（rocketmq-client-java 5.0.7）没有任何重试次数 setter，
     * 消费重试次数只能由 broker 订阅组配置决定，实际值以
     * {@code deploy/rocketmq/create-topics.sh} 的 {@code mqadmin updateSubGroup -r 16 -q 1}
     * 为准。本字段仅保留用于启动日志回显/文档口径，<b>不再参与任何客户端构建</b>，
     * 修改 yml 不会改变重试行为；后续小版本移除。
     */
    @Deprecated
    private int consumeMaxAttempts = 16;

    /**
     * 消费者后台注册的指数退避基数（毫秒）：第 n 次失败后等待 base·2^(n-1)，封顶 30s 并加
     * ±20% 抖动；重试无次数上限——Broker/Proxy 长时间不可用时 HTTP 服务照常，恢复后自动注册成功。
     */
    private long registerBackoffMillis = 3000L;

    /**
     * gRPC 请求超时秒数（底层 {@code requestTimeout}，同时作用于 telemetry settings 首轮
     * 同步）。客户端默认仅 3s：Proxy 滚动重启、单机 Docker Desktop 端口转发抖动或机器高负载
     * 时，消费者启动首轮 settings 同步极易超时（{@code Task was cancelled}），连续失败还会
     * 泄漏 ClientImpl 事件循环把后续重试也拖死。放宽到 10s 覆盖这些瞬态，真实故障仍由注册
     * 循环的无限退避重试兜底，不改变最终可用性语义。
     */
    private long requestTimeoutSeconds = 10L;

    /**
     * <b>R4-20：消费者启动宽限（毫秒），默认 20s。</b>
     *
     * <p>PushConsumer 的实际注册/拉取在 {@code ApplicationReadyEvent} 之后再延迟本时长才开始，
     * 必须覆盖的冷启动竞态（W7 全链实证）：
     * <ol>
     *   <li>Nacos 命名客户端首次服务列表推送在应用 Started 之后 ~15s 才到达——在此之前
     *       Feign 解析下游得到空实例列表，调用快速失败；</li>
     *   <li>broker 重启/积压场景下，消费者一注册就并发拉取数百条历史消息（实测 200
     *       消费线程在 30s 内拉取数千条陈旧关单消息），而下游 JVM JIT/连接池/Ribbon
     *       缓存全冷，前 10+ 次调用失败即把熔断打到 OPEN，随后半开探针在风暴尾部反复
     *       失败，熔断 2 分钟不恢复，E2E 全部下单路径 10008。</li>
     * </ol>
     *
     * <p>宽限期间消息留在 broker（生产侧 outbox 同事务持久不丢）；滚动发布期间由同组其他
     * 在线实例承担消费，新实例先把自身 HTTP 服务/JIT/服务发现热起来再接管队列。设为 0
     * 可关闭宽限（仅在不依赖任何下游 Feign 的消费者场景使用）。
     */
    private long consumeStartDelayMillis = 20_000L;

    /** 事件标准化配置（C15）。 */
    private Event event = new Event();

    /** 内部运维端点配置（C15，默认关闭）。 */
    private Admin admin = new Admin();

    @Data
    public static class Event {
        /**
         * 上游缺失 eventId 时是否由框架合成 {@code noid:{topic}:{bizKey|msgId}}。
         * true（默认）：合成确定性键注入消费上下文；false：无标识消息 ACK + ERROR 告警，
         * 不放入业务 listener。
         */
        private boolean syntheticEnabled = true;
    }

    @Data
    public static class Admin {
        /**
         * 是否暴露 actuator 端点 {@code /actuator/shopmqoutbox}（列 status=2 outbox + 手动
         * requeue）。默认 false；prod 仅允许内网 + 内部 token 打开。
         */
        private boolean enabled = false;
    }
}
