package com.shop.pay.feature.recon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.pay.feature.recon.entity.ReconDiff;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReconDiffMapper extends BaseMapper<ReconDiff> {

    /** 差错幂等落库（同批次同渠道同交易号唯一）。 */
    @Insert("INSERT IGNORE INTO t_pay_recon_diff "
            + "(batch_no, recon_date, channel_code, diff_type, pay_no, order_no, channel_txn_no, "
            + "local_amount_fen, channel_amount_fen, status, handle_action, handle_remark, "
            + "retry_count, max_retry, handle_time, deleted, create_time, update_time) "
            + "VALUES (#{batchNo}, #{reconDate}, #{channelCode}, #{diffType}, #{payNo}, #{orderNo}, "
            + "#{channelTxnNo}, #{localAmountFen}, #{channelAmountFen}, #{status}, #{handleAction}, "
            + "#{handleRemark}, #{retryCount}, #{maxRetry}, #{handleTime}, 0, NOW(), NOW())")
    int insertIgnore(ReconDiff diff);

    @Update("UPDATE t_pay_recon_diff SET status = #{toStatus}, handle_action = #{action}, "
            + "handle_remark = #{remark}, handle_time = #{handleTime} "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus,
                     @Param("action") String action,
                     @Param("remark") String remark,
                     @Param("handleTime") LocalDateTime handleTime);

    /** 补偿重试次数 +1（不超最大次数由业务层判断）。 */
    @Update("UPDATE t_pay_recon_diff SET retry_count = retry_count + 1, status = #{toStatus}, "
            + "handle_remark = #{remark} WHERE id = #{id} AND deleted = 0")
    int incrRetry(@Param("id") Long id,
                  @Param("toStatus") int toStatus,
                  @Param("remark") String remark);

    @Select("SELECT * FROM t_pay_recon_diff WHERE status IN (10,20) AND deleted = 0 "
            + "ORDER BY recon_date, id LIMIT #{limit}")
    List<ReconDiff> selectPending(@Param("limit") int limit);

    @Select("SELECT * FROM t_pay_recon_diff WHERE batch_no = #{batchNo} AND deleted = 0 ORDER BY id")
    List<ReconDiff> selectByBatch(@Param("batchNo") String batchNo);

    /** 批次内未处置（10/20）差异计数；批次状态由该计数推导（P2-4）。 */
    @Select("SELECT COUNT(1) FROM t_pay_recon_diff WHERE batch_no = #{batchNo} "
            + "AND status IN (10,20) AND deleted = 0")
    int countPendingByBatch(@Param("batchNo") String batchNo);
}
