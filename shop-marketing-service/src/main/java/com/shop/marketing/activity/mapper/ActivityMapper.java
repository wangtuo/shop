package com.shop.marketing.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.activity.entity.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {

    @Update("UPDATE t_activity SET status = #{toStatus}, version = version + 1 "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus);

    /**
     * W4-4/B2：秒杀到点自动结束扫描——type=10（秒杀）、在架(status=1)、开启 auto_end、
     * end_time 已到。CAS 翻态由 {@link #updateStatus} 完成，扫描本身不做状态变更。
     */
    @Select("SELECT * FROM t_activity WHERE type = 10 AND status = 1 AND auto_end = 1 "
            + "AND end_time <= NOW() AND deleted = 0")
    List<Activity> selectSeckillDue();

    /** B2 商户提交：仅草稿(0)/驳回(3) 可提交为待审核(1)。 */
    @Update("UPDATE t_activity SET audit_status = 1, submit_time = #{submitTime}, version = version + 1 "
            + "WHERE id = #{id} AND audit_status IN (0, 3) AND deleted = 0")
    int casSubmitAudit(@Param("id") Long id, @Param("submitTime") LocalDateTime submitTime);

    /** B2 平台审批：仅待审核(1) 可翻 通过(2)/驳回(3)。 */
    @Update("UPDATE t_activity SET audit_status = #{toAudit}, audit_user_id = #{auditUserId}, "
            + "audit_time = #{auditTime}, audit_remark = #{auditRemark}, version = version + 1 "
            + "WHERE id = #{id} AND audit_status = 1 AND deleted = 0")
    int casAudit(@Param("id") Long id, @Param("toAudit") int toAudit,
                 @Param("auditUserId") Long auditUserId, @Param("auditTime") LocalDateTime auditTime,
                 @Param("auditRemark") String auditRemark);
}
