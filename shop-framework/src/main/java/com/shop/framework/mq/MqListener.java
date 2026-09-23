package com.shop.framework.mq;

/**
 * 业务消费者接口。每个 Spring Bean 对应一个 consumer group，框架自动注册为 RocketMQ PushConsumer。
 *
 * <p>实现要点：
 * <ul>
 *   <li>{@link #onMessage(Object)} 抛异常默认视为消费失败，Broker 自动重试；</li>
 *   <li>参数/鉴权/签名/金额越界等终态 {@code BizException}（见 {@link MqErrorPolicy}）
 *       会被 ACK 丢弃并打 error 日志（重试无意义的毒丸消息），此类失败依赖对账兜底；</li>
 *   <li>消费逻辑必须以 eventId 或业务单号做幂等。</li>
 * </ul>
 */
public interface MqListener<T> {

    String topic();

    /** 默认订阅全部 Tag */
    default String tag() {
        return "*";
    }

    String consumerGroup();

    Class<T> type();

    void onMessage(T message);
}
