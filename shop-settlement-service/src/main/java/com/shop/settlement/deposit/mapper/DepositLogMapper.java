package com.shop.settlement.deposit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.deposit.entity.SettDepositLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 保证金流水 Mapper。 */
@Mapper
public interface DepositLogMapper extends BaseMapper<SettDepositLog> {

    /** 平台端分页查询保证金流水。 */
    @Select("SELECT * FROM t_sett_deposit_log WHERE deleted = 0 "
            + "AND (#{merchantId} IS NULL OR merchant_id = #{merchantId}) "
            + "ORDER BY id DESC LIMIT #{offset}, #{limit}")
    List<SettDepositLog> selectPage(@Param("merchantId") Long merchantId,
                                    @Param("offset") long offset,
                                    @Param("limit") int limit);

    /** 按支付单号查缴费流水（ORDER_PAID 到账入账用）。 */
    @Select("SELECT * FROM t_sett_deposit_log WHERE deleted = 0 AND pay_no = #{payNo} LIMIT 1")
    SettDepositLog selectByPayNo(@Param("payNo") String payNo);

    /**
     * 单据状态 CAS：仅当前 status = from 时置为 to。
     * @return 1 获胜（调用方执行后置动作）；0 状态已被并发推进/终态，调用方放弃
     */
    @Update("UPDATE t_sett_deposit_log SET status = #{to}, update_time = NOW() "
            + "WHERE log_no = #{logNo} AND status = #{from} AND deleted = 0")
    int casStatus(@Param("logNo") String logNo,
                  @Param("from") int from,
                  @Param("to") int to);

    /** 缴费支付单号回写：仅首次（pay_no 为空串时）写入，重试天然幂等。 */
    @Update("UPDATE t_sett_deposit_log SET pay_no = #{payNo}, update_time = NOW() "
            + "WHERE log_no = #{logNo} AND pay_no = '' AND deleted = 0")
    int updatePayNo(@Param("logNo") String logNo, @Param("payNo") String payNo);

    /** 退还代发受理登记：仅首次写入渠道流水号。 */
    @Update("UPDATE t_sett_deposit_log SET channel_remit_no = #{channelRemitNo}, update_time = NOW() "
            + "WHERE log_no = #{logNo} AND (channel_remit_no IS NULL OR channel_remit_no = '') AND deleted = 0")
    int markRemitAccepted(@Param("logNo") String logNo, @Param("channelRemitNo") String channelRemitNo);

    /**
     * 查询补偿 touch：领取一条待查询的退还单（status=10 已受理且到了可查询时间），
     * 多节点/多轮只有一个 CAS 获胜（ShedLock 之外的二道防线）。
     */
    @Update("UPDATE t_sett_deposit_log SET last_query_time = NOW(), update_time = NOW() "
            + "WHERE log_no = #{logNo} AND status = 10 AND deleted = 0 "
            + "AND (last_query_time IS NULL OR last_query_time < #{before})")
    int touchQuery(@Param("logNo") String logNo, @Param("before") LocalDateTime before);

    /** 待查询终态的退还打款单：log_type=40、status=10、渠道已受理。 */
    @Select("SELECT * FROM t_sett_deposit_log WHERE deleted = 0 AND log_type = 40 AND status = 10 "
            + "AND channel_remit_no IS NOT NULL AND channel_remit_no <> '' "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<SettDepositLog> selectRefundRemitPending(@Param("limit") int limit);

    /**
     * R4-24 保证金到账对账兜底：待支付缴费单（log_type=10、status=10、pay_no 已回写）且
     * 建单时间早于 {@code before}（避开支付→MQ 的正常在途窗口）。{@code last_query_time}
     * 兼作对账节流：查过支付域但未成功的单子按 {@code recheckBefore} 降频复查，防止大量
     * 废弃支付意向长期占满批次、阻塞新单子。
     * <p>R4-24 加固：排序以「从未查询」优先（IS NULL  DESC），其次 id 升序——废弃单堆积时
     * 新高 id stuck 行不会被 100 行/批的旧单节流窗口饿死。配套索引见 V8 迁移
     * idx_deposit_pay_recovery(log_type, status, deleted, id)。</p>
     */
    @Select("SELECT * FROM t_sett_deposit_log WHERE deleted = 0 AND log_type = 10 AND status = 10 "
            + "AND pay_no IS NOT NULL AND pay_no <> '' AND create_time < #{before} "
            + "AND (last_query_time IS NULL OR last_query_time < #{recheckBefore}) "
            + "ORDER BY (last_query_time IS NULL) DESC, id ASC LIMIT #{limit}")
    List<SettDepositLog> selectPayPendingForRecovery(@Param("before") java.time.LocalDateTime before,
                                                     @Param("recheckBefore") java.time.LocalDateTime recheckBefore,
                                                     @Param("limit") int limit);

    /** R4-24：对账已查过支付域（非成功态），落查询时间做降频节流；仅命中 status=10 行。 */
    @Update("UPDATE t_sett_deposit_log SET last_query_time = NOW(), update_time = NOW() "
            + "WHERE log_no = #{logNo} AND log_type = 10 AND status = 10 AND deleted = 0")
    int touchPayRecoveryQuery(@Param("logNo") String logNo);

    /**
     * R4-25 清退人工挂起预警边沿闸门：每笔 log40 退还单只允许挂起告警一次。
     * @return 1 本事务赢得告警权（每日重扫/多节点只有一个获胜方）；0 已挂起告警过，调用方静默跳过
     */
    @Update("UPDATE t_sett_deposit_log SET hang_alerted = 1, update_time = NOW() "
            + "WHERE log_no = #{logNo} AND deleted = 0 AND hang_alerted = 0")
    int casHangAlerted(@Param("logNo") String logNo);
}
