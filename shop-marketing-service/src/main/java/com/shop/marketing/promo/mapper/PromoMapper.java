package com.shop.marketing.promo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.promo.entity.Promo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PromoMapper extends BaseMapper<Promo> {

    /** 上架/下架条件更新（0↔1），影响 0 行视为状态冲突。 */
    @Update("UPDATE t_promo SET status = #{toStatus}, version = version + 1 "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus);

    /** 商户提交：仅草稿(0)/驳回(3) 可提交为待审核(1)。 */
    @Update("UPDATE t_promo SET audit_status = 1, submit_time = #{submitTime}, version = version + 1 "
            + "WHERE id = #{id} AND audit_status IN (0, 3) AND deleted = 0")
    int casSubmitAudit(@Param("id") Long id, @Param("submitTime") LocalDateTime submitTime);

    /** 平台审批：仅待审核(1) 可翻 通过(2)/驳回(3)。 */
    @Update("UPDATE t_promo SET audit_status = #{toAudit}, audit_user_id = #{auditUserId}, "
            + "audit_time = #{auditTime}, audit_remark = #{auditRemark}, version = version + 1 "
            + "WHERE id = #{id} AND audit_status = 1 AND deleted = 0")
    int casAudit(@Param("id") Long id, @Param("toAudit") int toAudit,
                 @Param("auditUserId") Long auditUserId, @Param("auditTime") LocalDateTime auditTime,
                 @Param("auditRemark") String auditRemark);
}
