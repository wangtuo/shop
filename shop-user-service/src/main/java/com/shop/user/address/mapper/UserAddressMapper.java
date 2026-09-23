package com.shop.user.address.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.user.address.entity.UserAddress;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddress> {

    /** 清除用户全部默认地址标记（设置新默认时同事务调用，保证仅 1 个默认） */
    @Update("UPDATE t_user_address SET is_default = 0, version = version + 1 "
            + "WHERE user_id = #{userId} AND is_default = 1 AND deleted = 0")
    int clearDefault(@Param("userId") Long userId);
}
