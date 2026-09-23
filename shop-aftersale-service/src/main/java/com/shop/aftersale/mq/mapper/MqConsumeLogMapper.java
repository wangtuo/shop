package com.shop.aftersale.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.aftersale.mq.entity.MqConsumeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MqConsumeLogMapper extends BaseMapper<MqConsumeLog> {

    /** INSERT IGNORE：重复 eventId 返回 0 行，直接 ACK。 */
    @Insert("INSERT IGNORE INTO t_aftersale_mq_consume(event_id, topic, biz_no, status, create_time) "
            + "VALUES(#{eventId}, #{topic}, #{bizNo}, 1, NOW())")
    int insertIgnore(@Param("eventId") String eventId,
                     @Param("topic") String topic,
                     @Param("bizNo") String bizNo);
}
