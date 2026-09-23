package com.shop.user.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.user.account.entity.UserPointsDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface UserPointsDailyMapper extends BaseMapper<UserPointsDaily> {

    /** 原子累加当日某场景获取积分（不存在则建），用于每日上限控制 */
    @Insert("INSERT INTO t_user_points_daily (id, user_id, stat_date, scene, earned_points, create_time, update_time, deleted) "
            + "VALUES (#{id}, #{userId}, #{statDate}, #{scene}, #{points}, NOW(), NOW(), 0) "
            + "ON DUPLICATE KEY UPDATE earned_points = earned_points + #{points}, update_time = NOW()")
    int addDaily(@Param("id") Long id,
                 @Param("userId") Long userId,
                 @Param("statDate") LocalDate statDate,
                 @Param("scene") Integer scene,
                 @Param("points") Long points);

    @Select("SELECT IFNULL(SUM(earned_points), 0) FROM t_user_points_daily "
            + "WHERE user_id = #{userId} AND stat_date = #{statDate} AND scene = #{scene} AND deleted = 0")
    Long sumDaily(@Param("userId") Long userId,
                  @Param("statDate") LocalDate statDate,
                  @Param("scene") Integer scene);
}
