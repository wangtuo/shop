package com.shop.user.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.user.account.entity.UserPointsGrant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface UserPointsGrantMapper extends BaseMapper<UserPointsGrant> {

    /**
     * FIFO：查询用户所有可用批次，按到期时间最早优先（同到期按批次先后）。
     * 积分消耗（下单实扣）按此顺序冲减。
     */
    @Select("SELECT * FROM t_user_points_grant WHERE user_id = #{userId} AND status = 0 AND deleted = 0 "
            + "AND points_remaining > 0 ORDER BY expire_time ASC, id ASC")
    List<UserPointsGrant> selectFifoAvailable(@Param("userId") Long userId);

    /** 条件冲减批次剩余，剩余不足影响 0 行（并发保护） */
    @Update("UPDATE t_user_points_grant SET points_remaining = points_remaining - #{points}, version = version + 1 "
            + "WHERE id = #{id} AND deleted = 0 AND points_remaining >= #{points}")
    int consumeRemaining(@Param("id") Long id, @Param("points") Long points);

    /** 到期清零：仅对仍有剩余的可用批次生效，返回影响行数 */
    @Update("UPDATE t_user_points_grant SET points_remaining = 0, status = 1, version = version + 1 "
            + "WHERE id = #{id} AND status = 0 AND points_remaining > 0 AND deleted = 0")
    int expireBatch(@Param("id") Long id);
}
