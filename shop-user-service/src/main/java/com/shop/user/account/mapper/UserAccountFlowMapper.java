package com.shop.user.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.user.account.entity.UserAccountFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountFlowMapper extends BaseMapper<UserAccountFlow> {

    /** 幂等查询：同 bizNo + changeType 的流水是否已存在 */
    @Select("SELECT * FROM t_user_account_flow WHERE biz_no = #{bizNo} AND change_type = #{changeType} AND deleted = 0 LIMIT 1")
    UserAccountFlow selectByBizAndType(@Param("bizNo") String bizNo, @Param("changeType") Integer changeType);
}
