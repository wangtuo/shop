package com.shop.marketing.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.common.entity.MarketingLock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MarketingLockMapper extends BaseMapper<MarketingLock> {

    @Update("UPDATE t_marketing_lock SET status = #{toStatus} "
            + "WHERE order_no = #{orderNo} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("orderNo") String orderNo, @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus);
}
