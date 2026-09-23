package com.shop.order.mq.service;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.order.mq.mapper.MqConsumeLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * MQ 消费幂等流水：INSERT IGNORE event_id，重复事件返回 false（直接 ACK）。
 * 必须与业务处理在同一事务中调用。
 *
 * <p>eventId 统一由框架 {@code EventNormalizer} 解析（header/body/合成 noid 键）并绑定
 * {@link MqConsumeContext}（C15）；本类不再私有拼接 {@code noid:topic:bizNo}，
 * 入参 eventId 缺失时回退取消费上下文，二者皆空才拒绝（正常链路不可达）。
 */
@Service
@RequiredArgsConstructor
public class MqConsumeService {

    private final MqConsumeLogMapper mapper;
    private final IdGenerator idGenerator;

    /**
     * @return true 首次出现（调用方继续处理）；false 重复事件（跳过）
     */
    public boolean firstTime(String eventId, String topic, String bizNo) {
        String resolved = (eventId == null || eventId.isBlank())
                ? MqConsumeContext.currentEventId()
                : eventId;
        if (resolved == null || resolved.isBlank()) {
            // 框架 EventNormalizer 已保证消费线程内 eventId 非空（含确定性合成）；
            // 走到这里说明非 MQ 线程误用且显式 eventId 缺失，拒绝落脏幂等键。
            throw new BizException(ErrorCode.PARAM_INVALID, "eventId 不能为空");
        }
        return mapper.insertIgnore(idGenerator.nextId(), resolved, topic, bizNo == null ? "" : bizNo) > 0;
    }
}
