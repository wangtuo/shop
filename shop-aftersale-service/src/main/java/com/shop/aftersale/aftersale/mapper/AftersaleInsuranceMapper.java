package com.shop.aftersale.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.aftersale.aftersale.entity.AftersaleInsurance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AftersaleInsuranceMapper extends BaseMapper<AftersaleInsurance> {

    @Select("SELECT * FROM t_aftersale_insurance WHERE order_no = #{orderNo} AND deleted = 0")
    AftersaleInsurance selectByOrderNo(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM t_aftersale_insurance WHERE status = 10 AND claim_deadline <= #{now} "
            + "AND deleted = 0 LIMIT #{limit}")
    List<AftersaleInsurance> selectClaimDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE t_aftersale_insurance SET status = 20, claim_time = #{claimTime}, update_time = NOW() "
            + "WHERE id = #{id} AND status = 10 AND deleted = 0")
    int markClaimed(@Param("id") Long id, @Param("claimTime") LocalDateTime claimTime);
}
