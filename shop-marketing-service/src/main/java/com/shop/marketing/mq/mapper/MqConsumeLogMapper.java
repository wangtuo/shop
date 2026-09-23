package com.shop.marketing.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.mq.entity.MqConsumeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MqConsumeLogMapper extends BaseMapper<MqConsumeLog> {

    /** event_id 唯一：重复事件影响 0 行直接 ACK；必须与业务处理在同一事务。 */
    @Insert("INSERT IGNORE INTO t_marketing_mq_consume(id, event_id, topic, biz_no, status, create_time, update_time) "
            + "VALUES(#{id}, #{eventId}, #{topic}, #{bizNo}, 0, NOW(), NOW())")
    int insertIgnore(@Param("id") Long id, @Param("eventId") String eventId,
                     @Param("topic") String topic, @Param("bizNo") String bizNo);
}
