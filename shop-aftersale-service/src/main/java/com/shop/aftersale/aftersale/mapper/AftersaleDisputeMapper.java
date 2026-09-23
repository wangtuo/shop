package com.shop.aftersale.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.aftersale.aftersale.entity.AftersaleDispute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AftersaleDisputeMapper extends BaseMapper<AftersaleDispute> {

    @Select("SELECT * FROM t_aftersale_dispute WHERE aftersale_no = #{aftersaleNo} AND deleted = 0")
    AftersaleDispute selectByAftersaleNo(@Param("aftersaleNo") String aftersaleNo);

    @Update("UPDATE t_aftersale_dispute SET status = #{toStatus}, update_time = NOW() "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("fromStatus") int fromStatus, @Param("toStatus") int toStatus);

    @Select("SELECT * FROM t_aftersale_dispute WHERE status = 10 AND evidence_deadline <= #{now} "
            + "AND deleted = 0 LIMIT #{limit}")
    List<AftersaleDispute> selectEvidenceDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * C8：商户 since 之后仍介入中（10 举证中 / 20 待裁决，≠30 已裁决）的介入单数。
     */
    @Select("SELECT COUNT(1) FROM t_aftersale_dispute WHERE merchant_id = #{merchantId} "
            + "AND status <> 30 AND create_time >= #{since} AND deleted = 0")
    long countRecentIntervene(@Param("merchantId") Long merchantId,
                              @Param("since") LocalDateTime since);
}
