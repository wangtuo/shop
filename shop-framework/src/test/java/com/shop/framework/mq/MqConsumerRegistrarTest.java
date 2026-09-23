package com.shop.framework.mq;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P2-3：registrar 按错误分类决定 ACK / 重试。 */
class MqConsumerRegistrarTest {

    private final MqProperties properties = new MqProperties();
    private final MqConsumerRegistrar registrar =
            new MqConsumerRegistrar(properties, Collections.emptyList());

    @AfterEach
    void tearDown() {
        // 释放后台注册线程池（守护线程，显式关闭避免测试 JVM 悬挂）
        registrar.stop();
    }

    private MqListener<String> listenerThrowing(RuntimeException ex) {
        return new MqListener<>() {
            @Override
            public String topic() {
                return "T";
            }

            @Override
            public String consumerGroup() {
                return "G";
            }

            @Override
            public Class<String> type() {
                return String.class;
            }

            @Override
            public void onMessage(String message) {
                throw ex;
            }
        };
    }

    private MessageView messageView() {
        MessageView mv = mock(MessageView.class);
        when(mv.getTag()).thenReturn(Optional.of("tag-a"));
        when(mv.getKeys()).thenReturn(Collections.singletonList("biz-1"));
        return mv;
    }

    @Test
    void 成功_ack() {
        MqListener<String> ok = new MqListener<>() {
            @Override
            public String topic() {
                return "T";
            }

            @Override
            public String consumerGroup() {
                return "G";
            }

            @Override
            public Class<String> type() {
                return String.class;
            }

            @Override
            public void onMessage(String message) {
            }
        };
        assertEquals(ConsumeResult.SUCCESS, registrar.invoke(ok, "{}", messageView()));
    }

    @Test
    void 终态业务错误_ack丢弃() {
        ConsumeResult result = registrar.invoke(
                listenerThrowing(new BizException(ErrorCode.PARAM_INVALID, "坏消息")), "{}", messageView());
        assertEquals(ConsumeResult.SUCCESS, result);
    }

    @Test
    void 可恢复错误_返回失败触发重试() {
        ConsumeResult result = registrar.invoke(
                listenerThrowing(new BizException(ErrorCode.CONFLICT)), "{}", messageView());
        assertEquals(ConsumeResult.FAILURE, result);
    }

    @Test
    void 非业务异常_返回失败触发重试() {
        ConsumeResult result = registrar.invoke(
                listenerThrowing(new RuntimeException("db timeout")), "{}", messageView());
        assertEquals(ConsumeResult.FAILURE, result);
    }

    /**
     * R4-20：ApplicationReadyEvent 后处于启动宽限期内，不得注册/拉取任何消费者
     * （broker 积压风暴必须等 JIT/服务发现预热后再接入），健康状态标记为宽限中。
     */
    @Test
    void 就绪事件后宽限期内不注册消费者() {
        properties.setConsumeStartDelayMillis(600_000L);
        MqConsumerRegistrar delayed = new MqConsumerRegistrar(
                properties, List.of(listenerThrowing(new BizException(ErrorCode.CONFLICT))));
        try {
            delayed.start();
            // ReadyEvent 之前：尚未调度
            assertEquals(0, delayed.registeredCount());
            assertFalse(delayed.isInStartupGrace());
            delayed.onApplicationReady();
            // 宽限中：不允许注册任何 PushConsumer（不会去连 broker），并对外标记宽限态
            assertTrue(delayed.isInStartupGrace());
            assertEquals(0, delayed.registeredCount());
            assertFalse(delayed.allRegistered());
        } finally {
            delayed.stop();
        }
    }

    /** R4-20：宽限为 0 时（显式关闭）行为退化为就绪后立即允许注册调度，不进入宽限态。 */
    @Test
    void 宽限关闭时不进入宽限态() {
        properties.setConsumeStartDelayMillis(0L);
        MqConsumerRegistrar immediate = new MqConsumerRegistrar(
                properties, List.of(listenerThrowing(new BizException(ErrorCode.CONFLICT))));
        try {
            immediate.start();
            immediate.onApplicationReady();
            assertFalse(immediate.isInStartupGrace());
        } finally {
            immediate.stop();
        }
    }
}
