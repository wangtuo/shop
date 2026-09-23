package com.shop.settlement.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.mq.entity.SettMqConsume;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MQ 消费幂等流水 Mapper。 */
@Mapper
public interface MqConsumeMapper extends BaseMapper<SettMqConsume> {

    /**
     * INSERT IGNORE 登记消费流水：首次返回 1，重复键返回 0（直接 ACK 跳过）。
     * 必须与业务处理在同一事务内，业务失败回滚后可重新消费。
     *
     * <p>R4-24：唯一键为 uk_event_group(event_id, consumer_group)——同一事件扇出到
     * 多个消费组（如 shop_order_paid 同时被 cg_sett_paid 与 cg_sett_deposit_pay 订阅）
     * 必须各自独立登记；旧的单列 uk_event_id 会使后登记的组静默跳过业务、消息照常 ACK，
     * 造成保证金到账静默不入账。</p>
     */
    @Insert("INSERT IGNORE INTO t_sett_mq_consume "
            + "(event_id, topic, consumer_group, biz_no, status, create_time, update_time, deleted) "
            + "VALUES (#{eventId}, #{topic}, #{consumerGroup}, #{bizNo}, 1, NOW(), NOW(), 0)")
    int insertIgnore(@Param("eventId") String eventId,
                     @Param("topic") String topic,
                     @Param("consumerGroup") String consumerGroup,
                     @Param("bizNo") String bizNo);
}
