package com.shop.marketing.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.coupon.entity.Coupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CouponMapper extends BaseMapper<Coupon> {

    /** B6：新人礼包在发模板（issue_way=3、new_user_gift=1、上架中）。 */
    @Select("SELECT * FROM t_coupon WHERE issue_way = 3 AND new_user_gift = 1 AND status = 1 AND deleted = 0")
    List<Coupon> selectNewUserGiftTemplates();

    /** 条件扣减库存：总量为 0 表示不限量；影响 0 行即已领完。 */
    @Update("UPDATE t_coupon SET received_count = received_count + 1, version = version + 1 "
            + "WHERE id = #{id} AND status = 1 AND deleted = 0 "
            + "AND (total_count = 0 OR received_count < total_count)")
    int increaseReceived(@Param("id") Long id);

    @Update("UPDATE t_coupon SET received_count = received_count - 1, version = version + 1 "
            + "WHERE id = #{id} AND received_count > 0 AND deleted = 0")
    int decreaseReceived(@Param("id") Long id);

    @Update("UPDATE t_coupon SET status = #{toStatus}, version = version + 1 "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus);

    /** 商户提交：仅草稿(0)/驳回(3) 可提交为待审核(1)。 */
    @Update("UPDATE t_coupon SET audit_status = 1, submit_time = #{submitTime}, version = version + 1 "
            + "WHERE id = #{id} AND audit_status IN (0, 3) AND deleted = 0")
    int casSubmitAudit(@Param("id") Long id, @Param("submitTime") LocalDateTime submitTime);

    /** 平台审批：仅待审核(1) 可翻 通过(2)/驳回(3)。 */
    @Update("UPDATE t_coupon SET audit_status = #{toAudit}, audit_user_id = #{auditUserId}, "
            + "audit_time = #{auditTime}, audit_remark = #{auditRemark}, version = version + 1 "
            + "WHERE id = #{id} AND audit_status = 1 AND deleted = 0")
    int casAudit(@Param("id") Long id, @Param("toAudit") int toAudit,
                 @Param("auditUserId") Long auditUserId, @Param("auditTime") LocalDateTime auditTime,
                 @Param("auditRemark") String auditRemark);
}
