package com.shop.pay.feature.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.pay.feature.payment.entity.Payment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    /**
     * 重新支付时让终态旧单入墓碑槽位：active_slot 0 → 自身 id。
     * 仅当旧单仍活跃（active_slot=0）且状态为 FAIL(40)/CLOSED(50) 时生效；
     * 影响 1 行才腾出 (order_no, 0) 槽位，0 行即并发落败/状态已被推进，调用方必须重读活跃行幂等返回。
     */
    @Update("UPDATE t_pay_order SET active_slot = id, version = version + 1 "
            + "WHERE id = #{id} AND active_slot = 0 AND status IN (40,50) AND deleted = 0")
    int retireActiveSlot(@Param("id") Long id);

    /**
     * 取订单当前活跃支付单（active_slot=0）。多次支付尝试的历史终态行
     * （active_slot=自身 id）不命中；回调/主动查询/退款按 payNo 直达具体行，不走此语义。
     */
    @Select("SELECT * FROM t_pay_order WHERE order_no = #{orderNo} AND active_slot = 0 AND deleted = 0 LIMIT 1")
    Payment selectActiveByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 退款按订单号定位原支付单：仅成功系状态（30 成功 / 60 退款中 / 70 已退款）的活跃行。
     * 显式 status + active_slot 双过滤：重新支付场景下历史 FAIL/CLOSED 行已入墓碑槽位，
     * 不会被误当原支付单，防止串单退款（addRefundedFen 必须落在成功行）。
     */
    @Select("SELECT * FROM t_pay_order WHERE order_no = #{orderNo} AND active_slot = 0 "
            + "AND status IN (30,60,70) AND deleted = 0 LIMIT 1")
    Payment selectRefundableByOrderNo(@Param("orderNo") String orderNo);

    /** 回调/主动查询落单：10/20 → 30 成功。影响 0 行即已被并发处理，天然幂等。 */
    @Update("UPDATE t_pay_order SET status = 30, channel_transaction_no = #{channelTxnNo}, "
            + "notify_id = #{notifyId}, pay_time = #{payTime}, version = version + 1 "
            + "WHERE pay_no = #{payNo} AND status IN (10,20) AND deleted = 0")
    int markSuccess(@Param("payNo") String payNo,
                    @Param("channelTxnNo") String channelTxnNo,
                    @Param("notifyId") String notifyId,
                    @Param("payTime") LocalDateTime payTime);

    /** 支付失败：10/20 → 40。 */
    @Update("UPDATE t_pay_order SET status = 40, fail_reason = #{reason}, version = version + 1 "
            + "WHERE pay_no = #{payNo} AND status IN (10,20) AND deleted = 0")
    int markFail(@Param("payNo") String payNo, @Param("reason") String reason);

    /** 超时关单：10/20 → 50。 */
    @Update("UPDATE t_pay_order SET status = 50, close_time = #{now}, version = version + 1 "
            + "WHERE pay_no = #{payNo} AND status IN (10,20) AND deleted = 0")
    int markClosed(@Param("payNo") String payNo, @Param("now") LocalDateTime now);

    /** 部分退款：30 → 60 退款中（已在 60 时影响 0 行，幂等）。 */
    @Update("UPDATE t_pay_order SET status = 60, version = version + 1 "
            + "WHERE pay_no = #{payNo} AND status = 30 AND deleted = 0")
    int enterRefunding(@Param("payNo") String payNo);

    /** 全额退款完成：30/60 → 70。 */
    @Update("UPDATE t_pay_order SET status = 70, version = version + 1 "
            + "WHERE pay_no = #{payNo} AND status IN (30,60) AND deleted = 0")
    int markRefunded(@Param("payNo") String payNo);

    /**
     * 累计退款金额条件更新（防透支）：累计退款不得超过实付金额。
     * 影响 0 行抛 REFUND_AMOUNT_ERROR。
     */
    @Update("UPDATE t_pay_order SET refunded_fen = refunded_fen + #{amountFen}, version = version + 1 "
            + "WHERE pay_no = #{payNo} AND refunded_fen + #{amountFen} <= amount_fen "
            + "AND status IN (30,60) AND deleted = 0")
    int addRefundedFen(@Param("payNo") String payNo, @Param("amountFen") long amountFen);

    /** 对账金额不符：以渠道为准调账。 */
    @Update("UPDATE t_pay_order SET amount_fen = #{channelAmountFen}, version = version + 1 "
            + "WHERE pay_no = #{payNo} AND deleted = 0")
    int adjustAmount(@Param("payNo") String payNo, @Param("channelAmountFen") long channelAmountFen);

    /** 超时扫描：10/20 且 expire_time 已过。 */
    @Select("SELECT * FROM t_pay_order WHERE status IN (10,20) AND expire_time IS NOT NULL "
            + "AND expire_time < #{now} AND deleted = 0 ORDER BY expire_time LIMIT #{limit}")
    List<Payment> selectTimeout(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** T+1 对账：指定自然日内支付成功的本地单。 */
    @Select("SELECT * FROM t_pay_order WHERE status IN (30,60,70) "
            + "AND pay_time >= #{start} AND pay_time < #{end} AND deleted = 0")
    List<Payment> selectSuccessBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
