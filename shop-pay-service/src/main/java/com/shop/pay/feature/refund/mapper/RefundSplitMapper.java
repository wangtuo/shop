package com.shop.pay.feature.refund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.pay.feature.refund.entity.RefundSplit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RefundSplitMapper extends BaseMapper<RefundSplit> {

    @Update("UPDATE t_pay_refund_split SET status = 20 WHERE id = #{id} AND status = 10 AND deleted = 0")
    int markProcessing(@Param("id") Long id);

    /**
     * P2-5 三段式 TX1：退款单维度批量 10（新单）/40（失败重试单）→ 20，与退款单 PROCESSING 同短事务。
     * 已成功(30)的 split 不动（retry 时不重复打款）。
     */
    @Update("UPDATE t_pay_refund_split SET status = 20 WHERE refund_no = #{refundNo} "
            + "AND status IN (10,40) AND deleted = 0")
    int markProcessingByRefundNo(@Param("refundNo") String refundNo);

    @Update("UPDATE t_pay_refund_split SET status = 30, channel_refund_no = #{channelRefundNo}, "
            + "finish_time = #{finishTime} WHERE id = #{id} AND status IN (10,20) AND deleted = 0")
    int markSuccess(@Param("id") Long id,
                    @Param("channelRefundNo") String channelRefundNo,
                    @Param("finishTime") LocalDateTime finishTime);

    @Update("UPDATE t_pay_refund_split SET status = 40 WHERE id = #{id} AND status IN (10,20) AND deleted = 0")
    int markFail(@Param("id") Long id);

    @Select("SELECT * FROM t_pay_refund_split WHERE refund_no = #{refundNo} AND deleted = 0 ORDER BY id")
    List<RefundSplit> selectByRefundNo(@Param("refundNo") String refundNo);
}
