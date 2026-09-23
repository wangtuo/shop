package com.shop.user.profile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.user.profile.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 条件更新签到状态：仅当今天尚未签到时生效，影响 0 行表示今日已签到（并发/重复）。
     */
    @Update("UPDATE t_user SET last_sign_date = #{today}, continuous_days = #{days}, version = version + 1 "
            + "WHERE id = #{userId} AND deleted = 0 "
            + "AND (last_sign_date IS NULL OR last_sign_date < #{today})")
    int applySignIn(@Param("userId") Long userId,
                    @Param("today") LocalDate today,
                    @Param("days") Integer days);

    /** 更新成长值与等级（成长值变更后重算等级） */
    @Update("UPDATE t_user SET growth = #{growth}, level = #{level}, version = version + 1 "
            + "WHERE id = #{userId} AND deleted = 0")
    int updateGrowth(@Param("userId") Long userId,
                     @Param("growth") Long growth,
                     @Param("level") Integer level);
}
