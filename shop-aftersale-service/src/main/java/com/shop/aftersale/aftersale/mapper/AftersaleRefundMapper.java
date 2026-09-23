package com.shop.aftersale.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.aftersale.aftersale.entity.AftersaleRefund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AftersaleRefundMapper extends BaseMapper<AftersaleRefund> {

    @Select("SELECT * FROM t_aftersale_refund WHERE aftersale_no = #{aftersaleNo} AND deleted = 0")
    AftersaleRefund selectByAftersaleNo(@Param("aftersaleNo") String aftersaleNo);

    @Select("SELECT * FROM t_aftersale_refund WHERE refund_no = #{refundNo} AND deleted = 0")
    AftersaleRefund selectByRefundNo(@Param("refundNo") String refundNo);

    @Update("UPDATE t_aftersale_refund SET status = #{toStatus}, pay_method = #{payMethod}, "
            + "refund_time = #{refundTime}, update_time = NOW() "
            + "WHERE refund_no = #{refundNo} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("refundNo") String refundNo,
                     @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus,
                     @Param("payMethod") Integer payMethod,
                     @Param("refundTime") LocalDateTime refundTime);

    /** P2-8：置失败（不限原状态），供 REQUIRES_NEW 独立事务在调支付域失败后落库。 */
    @Update("UPDATE t_aftersale_refund SET status = 40, fail_reason = #{failReason}, update_time = NOW() "
            + "WHERE refund_no = #{refundNo} AND deleted = 0")
    int markFail(@Param("refundNo") String refundNo, @Param("failReason") String failReason);
}
