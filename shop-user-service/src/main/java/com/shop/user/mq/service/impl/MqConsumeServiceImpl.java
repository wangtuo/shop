package com.shop.user.mq.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.user.mq.entity.UserMqConsume;
import com.shop.user.mq.mapper.UserMqConsumeMapper;
import com.shop.user.mq.service.MqConsumeService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MqConsumeServiceImpl implements MqConsumeService {

    private static final Logger log = LoggerFactory.getLogger(MqConsumeServiceImpl.class);

    private final UserMqConsumeMapper consumeMapper;
    private final IdGenerator idGenerator;

    @Override
    public boolean beginConsume(String topic, String consumerGroup, String bizNo) {
        // eventId 由框架 EventNormalizer 统一解析（缺失时确定性合成 noid 键）并绑定上下文，
        // listener 执行期间必非空；本服务不再做私有判空/拼接。
        String eventId = MqConsumeContext.currentEventId();
        Long exists = consumeMapper.selectCount(new LambdaQueryWrapper<UserMqConsume>()
                .eq(UserMqConsume::getEventId, eventId));
        if (exists != null && exists > 0) {
            log.info("重复MQ事件直接ACK eventId={} group={}", eventId, consumerGroup);
            return false;
        }
        UserMqConsume record = new UserMqConsume();
        record.setId(idGenerator.nextId());
        record.setEventId(eventId);
        record.setTopic(topic);
        record.setConsumerGroup(consumerGroup);
        record.setBizNo(bizNo);
        record.setStatus(1);
        try {
            consumeMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("并发重复MQ事件直接ACK eventId={} group={}", eventId, consumerGroup);
            return false;
        }
        return true;
    }
}
