package com.shop.order.mq.service;

import com.shop.common.exception.BizException;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.order.mq.mapper.MqConsumeLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQ 消费幂等流水单测（W1-D 残留清理后）：
 * 私有 {@code noid:topic:bizNo} 拼接已删除——eventId 统一由框架 EventNormalizer 解析并经
 * MqConsumeContext 提供；本服务只做显式 eventId → 上下文 eventId → 拒绝 三级取值。
 */
@ExtendWith(MockitoExtension.class)
class MqConsumeServiceTest {

    @Mock
    private MqConsumeLogMapper mapper;
    @Mock
    private IdGenerator idGenerator;

    @InjectMocks
    private MqConsumeService service;

    @AfterEach
    void clearContext() {
        MqConsumeContext.clear();
    }

    @Test
    void firstTime_inserted_returnsTrue() {
        when(idGenerator.nextId()).thenReturn(100L);
        when(mapper.insertIgnore(eq(100L), eq("evt-1"), eq("topicA"), eq("biz-1"))).thenReturn(1);

        assertThat(service.firstTime("evt-1", "topicA", "biz-1")).isTrue();
    }

    @Test
    void firstTime_duplicateEvent_returnsFalse() {
        when(idGenerator.nextId()).thenReturn(101L);
        when(mapper.insertIgnore(anyLong(), eq("evt-1"), eq("topicA"), eq("biz-1"))).thenReturn(0);

        assertThat(service.firstTime("evt-1", "topicA", "biz-1")).isFalse();
    }

    @Test
    void firstTime_blankEventId_fallsBackToFrameworkContext() {
        MqConsumeContext.bind(new MqConsumeContext("noid:topicA:biz-1", "topicA", "biz-1", "msg-1", true));
        when(idGenerator.nextId()).thenReturn(102L);
        when(mapper.insertIgnore(eq(102L), eq("noid:topicA:biz-1"), eq("topicA"), eq("biz-1")))
                .thenReturn(1);

        // 不再私有拼接：同一入参在框架上下文存在时直接采信框架合成值
        assertThat(service.firstTime("  ", "topicA", "biz-1")).isTrue();
        verify(mapper).insertIgnore(eq(102L), eq("noid:topicA:biz-1"), eq("topicA"), eq("biz-1"));
    }

    @Test
    void firstTime_blankEventId_withoutContext_throwsParamInvalid() {
        // 非 MQ 线程误用且未显式给 eventId：拒绝落脏幂等键（正常消费链路由框架保证不可达）
        assertThatThrownBy(() -> service.firstTime(null, "topicA", "biz-1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void firstTime_nullBizNo_persistsEmptyString() {
        when(idGenerator.nextId()).thenReturn(103L);
        when(mapper.insertIgnore(eq(103L), eq("evt-2"), eq("topicA"), eq(""))).thenReturn(1);

        assertThat(service.firstTime("evt-2", "topicA", null)).isTrue();
    }
}
