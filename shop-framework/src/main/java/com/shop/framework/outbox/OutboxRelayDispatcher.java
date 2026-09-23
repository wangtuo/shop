package com.shop.framework.outbox;

import com.shop.framework.mq.MqProducer;
import com.shop.framework.outbox.entity.OutboxMessage;
import com.shop.framework.outbox.mapper.OutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单条 outbox 事件的事务性投递（从 {@link OutboxRelayJob} 拆出的独立 Bean）。
 *
 * <p>必须是独立 Spring Bean：{@code @Transactional} 基于代理生效，若由 RelayJob
 * 内部自调用（this.relayOne(...)），代理被绕过、事务不生效，CAS 置位与失败记账会在
 * MyBatis 自动提交下逐条裸奔，丧失「记账与发送结果原子可见」的语义。
 *
 * <p>传播级别 REQUIRES_NEW：每条事件独立事务，单条失败只回滚本条，不影响同批其他事件；
 * 也不与调用方（定时任务无事务）共享事务生命周期。
 */
@Component
public class OutboxRelayDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayDispatcher.class);

    private final OutboxMapper outboxMapper;
    private final MqProducer mqProducer;
    private final int maxRetry;

    public OutboxRelayDispatcher(OutboxMapper outboxMapper, MqProducer mqProducer,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${shop.outbox.max-retry:20}") int maxRetry) {
        this.outboxMapper = outboxMapper;
        this.mqProducer = mqProducer;
        this.maxRetry = maxRetry;
    }

    /**
     * 投递一条事件并记账。到 deliverAt 的延时消息与普通消息统一即时发送
     * （延时由 outbox 表 deliver_at 承担）。异常不外抛，保证整批继续。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void dispatch(OutboxMessage msg) {
        try {
            mqProducer.sendRaw(msg.getTopic(), blankToNull(msg.getTag()),
                    msg.getBodyJson(), msg.getBizKey());
            int rows = outboxMapper.markSent(msg.getId());
            if (rows == 0) {
                log.info("outbox 事件已被其他路径置位，跳过 id={} topic={}", msg.getId(), msg.getTopic());
            }
        } catch (Exception e) {
            String err = truncate(e.toString());
            outboxMapper.recordFailure(msg.getId(), maxRetry, err);
            log.warn("outbox 投递失败，退避重试 id={} topic={} retry={} err={}",
                    msg.getId(), msg.getTopic(), msg.getRetryCount() + 1, err);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 900 ? s : s.substring(0, 900);
    }
}
