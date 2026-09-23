package com.shop.marketing.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.activity.entity.GroupbuyMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GroupbuyMemberMapper extends BaseMapper<GroupbuyMember> {

    /** 参团 → 成团。 */
    @Update("UPDATE t_groupbuy_member SET status = 1 WHERE group_no = #{groupNo} AND status = 0 AND deleted = 0")
    int markSuccess(@Param("groupNo") String groupNo);

    /** 参团中 → 已退出（订单取消/超时）。 */
    @Update("UPDATE t_groupbuy_member SET status = 2 WHERE order_no = #{orderNo} AND status = 0 AND deleted = 0")
    int markLeave(@Param("orderNo") String orderNo);
}
