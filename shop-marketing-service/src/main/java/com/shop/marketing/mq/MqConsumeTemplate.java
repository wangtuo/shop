package com.shop.marketing.mq;

import com.shop.framework.id.IdGenerator;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.marketing.mq.mapper.MqConsumeLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * MQ 消费幂等模板：INSERT IGNORE 流水与业务处理同事务；重复 eventId 直接跳过（ACK）。
 */
@Component
@RequiredArgsConstructor
public class MqConsumeTemplate {

    private final MqConsumeLogMapper mapper;
    private final IdGenerator idGenerator;

    @Transactional(rollbackFor = Exception.class)
    public void runOnce(String eventId, String topic, String bizNo, Runnable action) {
        int rows = mapper.insertIgnore(idGenerator.nextId(), resolveEventId(eventId, topic), topic, bizNo);
        if (rows == 0) {
            return;
        }
        action.run();
    }

    /**
     * R4-27：eventId 优先用消息体信封值（BaseEvent 随机 UUID，活路径恒非空）；空白时回退
     * 框架 EventNormalizer 绑定的上下文 eventId（header / noid 合成），避免空键写库；
     * 非 MQ 线程误调用且两源皆空属编程错误，fail-fast。
     */
    static String resolveEventId(String bodyEventId, String topic) {
        if (bodyEventId != null && !bodyEventId.isBlank()) {
            return bodyEventId;
        }
        String ctx = MqConsumeContext.currentEventId();
        if (ctx == null || ctx.isBlank()) {
            throw new IllegalStateException("消费事件 eventId 为空且无 MQ 上下文，topic=" + topic);
        }
        return ctx;
    }

    /** 无流水落库需求时的占位（保留传播语义显式声明）。 */
    @Transactional(propagation = Propagation.SUPPORTS)
    public void noop() {
    }
}
