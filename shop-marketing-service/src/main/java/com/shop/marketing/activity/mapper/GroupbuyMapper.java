package com.shop.marketing.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.activity.entity.Groupbuy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface GroupbuyMapper extends BaseMapper<Groupbuy> {

    /** 参团人数 +1（未满员且进行中）。 */
    @Update("UPDATE t_groupbuy SET joined_count = joined_count + 1, version = version + 1 "
            + "WHERE group_no = #{groupNo} AND status = 0 AND joined_count < required_people AND deleted = 0")
    int joinGroup(@Param("groupNo") String groupNo);

    /** 订单取消导致成员退出：人数 -1（不会低于 1）。 */
    @Update("UPDATE t_groupbuy SET joined_count = joined_count - 1, version = version + 1 "
            + "WHERE group_no = #{groupNo} AND status = 0 AND joined_count > 1 AND deleted = 0")
    int leaveGroup(@Param("groupNo") String groupNo);

    /** 满员成团：0 → 1。 */
    @Update("UPDATE t_groupbuy SET status = 1, success_time = #{now}, version = version + 1 "
            + "WHERE group_no = #{groupNo} AND status = 0 AND joined_count >= required_people AND deleted = 0")
    int markSuccess(@Param("groupNo") String groupNo, @Param("now") LocalDateTime now);

    /** 24h 超时未成团：0 → 2（失败自动退款由事件驱动），单团条件更新防与成团并发冲突。 */
    @Update("UPDATE t_groupbuy SET status = 2, version = version + 1 "
            + "WHERE group_no = #{groupNo} AND status = 0 AND expire_time < #{now} AND deleted = 0")
    int markExpired(@Param("groupNo") String groupNo, @Param("now") LocalDateTime now);
}
