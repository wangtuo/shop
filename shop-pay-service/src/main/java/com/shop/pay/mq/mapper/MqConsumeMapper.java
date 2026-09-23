package com.shop.pay.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.pay.mq.entity.MqConsumeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MqConsumeMapper extends BaseMapper<MqConsumeLog> {

    /** 消费幂等流水：同消费组同 eventId 已存在则忽略（返回 0 行），消费方直接 ACK。 */
    @Insert("INSERT IGNORE INTO t_pay_mq_consume "
            + "(topic, event_id, biz_no, consumer_group, consume_status, payload, error_msg, "
            + "deleted, create_time, update_time) "
            + "VALUES (#{topic}, #{eventId}, #{bizNo}, #{consumerGroup}, #{consumeStatus}, "
            + "#{payload}, #{errorMsg}, 0, NOW(), NOW())")
    int insertIgnore(MqConsumeLog log);
}
