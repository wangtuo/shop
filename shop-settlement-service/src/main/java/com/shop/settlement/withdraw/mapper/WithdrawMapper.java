package com.shop.settlement.withdraw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.withdraw.entity.SettWithdraw;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 提现单 Mapper。 */
@Mapper
public interface WithdrawMapper extends BaseMapper<SettWithdraw> {

    /** 审核批次：取状态 10 的申请单。 */
    @Select("SELECT * FROM t_sett_withdraw WHERE deleted = 0 AND status = 10 "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<SettWithdraw> selectApplying(@Param("limit") int limit);

    /**
     * 打款提交批次：状态 20、申请日早于今日（T+1）且渠道尚未受理
     *（channel_remit_no 为空）。已受理待终态的单由 RemitQueryJob 查询推进，不再重复提交。
     */
    @Select("SELECT * FROM t_sett_withdraw WHERE deleted = 0 AND status = 20 "
            + "AND apply_date < #{today} "
            + "AND (channel_remit_no IS NULL OR channel_remit_no = '') "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<SettWithdraw> selectRemittable(@Param("today") LocalDate today, @Param("limit") int limit);

    /** 终态查询批：状态 20 且渠道已受理，按查询节流条件取出。 */
    @Select("SELECT * FROM t_sett_withdraw WHERE deleted = 0 AND status = 20 "
            + "AND channel_remit_no IS NOT NULL AND channel_remit_no <> '' "
            + "AND (last_query_time IS NULL OR last_query_time < #{before}) "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<SettWithdraw> selectRemitQueryPending(@Param("before") LocalDateTime before,
                                               @Param("limit") int limit);

    /** 受理登记 CAS：仅首次写入渠道流水号（防批次重试/多节点重复代发）。 */
    @Update("UPDATE t_sett_withdraw SET channel_remit_no = #{channelRemitNo}, "
            + "remit_fail_reason = '', version = version + 1, update_time = NOW() "
            + "WHERE id = #{id} AND status = 20 AND deleted = 0 "
            + "AND (channel_remit_no IS NULL OR channel_remit_no = '')")
    int casMarkRemitNo(@Param("id") Long id, @Param("channelRemitNo") String channelRemitNo);

    /** 查询 touch CAS：领取查询权并累加 query_count。 */
    @Update("UPDATE t_sett_withdraw SET last_query_time = NOW(), query_count = query_count + 1, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE withdraw_no = #{withdrawNo} AND status = 20 AND deleted = 0 "
            + "AND (last_query_time IS NULL OR last_query_time < #{before})")
    int touchQuery(@Param("withdrawNo") String withdrawNo, @Param("before") LocalDateTime before);

    /** 终态成功 CAS 20→30：只有获胜方执行解冻出款/手续费/outbox。 */
    @Update("UPDATE t_sett_withdraw SET status = 30, remit_time = NOW(), "
            + "version = version + 1, update_time = NOW() "
            + "WHERE id = #{id} AND status = 20 AND deleted = 0 "
            + "AND channel_remit_no IS NOT NULL AND channel_remit_no <> ''")
    int casRemitSuccess(@Param("id") Long id);

    /** 商户最近一次成功提现单（清退退还复用收款账户）。 */
    @Select("SELECT * FROM t_sett_withdraw WHERE deleted = 0 AND merchant_id = #{merchantId} "
            + "AND status = 30 ORDER BY id DESC LIMIT 1")
    SettWithdraw selectLatestSuccess(@Param("merchantId") long merchantId);
}
