package com.shop.marketing.activity.groupbuy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.activity.groupbuy.entity.GroupbuyEventTodo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface GroupbuyEventTodoMapper extends BaseMapper<GroupbuyEventTodo> {

    /** event_id 唯一：重复事件影响 0 行，再回查拿现存行；先于订单域调用落库（状态 0）。 */
    @Insert("INSERT IGNORE INTO t_groupbuy_event_todo"
            + "(id, event_id, group_no, activity_id, order_no, user_id, leader_flag, op_type,"
            + " handle_status, retry_count, create_time, update_time) "
            + "VALUES(#{id}, #{eventId}, #{groupNo}, #{activityId}, #{orderNo}, #{userId}, #{leaderFlag},"
            + " #{opType}, 0, 0, NOW(), NOW())")
    int insertIgnore(GroupbuyEventTodo todo);

    @Select("SELECT * FROM t_groupbuy_event_todo WHERE event_id = #{eventId}")
    GroupbuyEventTodo selectByEventId(@Param("eventId") String eventId);

    /** 第二道幂等：同团同成员同操作类型（groupNo + orderNo + opType）。 */
    @Select("SELECT * FROM t_groupbuy_event_todo WHERE group_no = #{groupNo} AND order_no = #{orderNo}"
            + " AND op_type = #{opType} LIMIT 1")
    GroupbuyEventTodo selectByBizKey(@Param("groupNo") String groupNo, @Param("orderNo") String orderNo,
                                     @Param("opType") int opType);

    /** 待补偿：0 待处理 / 2 失败待重试，retry_count 达上限不再扫描（退避在 service 侧按 update_time 判定）。 */
    @Select("SELECT * FROM t_groupbuy_event_todo WHERE handle_status IN (0, 2) AND retry_count < #{maxRetry}"
            + " ORDER BY id ASC LIMIT #{limit}")
    List<GroupbuyEventTodo> selectRetryable(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    /** 仅在非终态时翻 1，防止重试与补偿并发重复通知。 */
    @Update("UPDATE t_groupbuy_event_todo SET handle_status = 1, update_time = NOW() "
            + "WHERE id = #{id} AND handle_status <> 1")
    int markHandled(@Param("id") Long id);

    /** 失败计数 +1，保持状态 2；已达上限的行不再累加（由 Job 告警）。 */
    @Update("UPDATE t_groupbuy_event_todo SET handle_status = 2, retry_count = retry_count + 1, update_time = NOW()"
            + " WHERE id = #{id} AND retry_count < #{maxRetry}")
    int markRetry(@Param("id") Long id, @Param("maxRetry") int maxRetry);
}
