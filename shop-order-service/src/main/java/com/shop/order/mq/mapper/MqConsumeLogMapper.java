package com.shop.order.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.order.mq.entity.MqConsumeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MqConsumeLogMapper extends BaseMapper<MqConsumeLog> {

    /**
     * 幂等流水落库：event_id 唯一，重复事件影响 0 行直接 ACK。
     * 必须与业务处理在同一事务内执行。
     */
    @Insert("INSERT IGNORE INTO t_order_mq_consume(id, event_id, topic, biz_no, status, create_time, update_time) "
            + "VALUES(#{id}, #{eventId}, #{topic}, #{bizNo}, 1, NOW(), NOW())")
    int insertIgnore(@Param("id") Long id, @Param("eventId") String eventId,
                     @Param("topic") String topic, @Param("bizNo") String bizNo);
}
