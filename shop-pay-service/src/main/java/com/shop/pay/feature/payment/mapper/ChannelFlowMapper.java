package com.shop.pay.feature.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChannelFlowMapper extends BaseMapper<ChannelFlow> {

    /** 单行流水成功：10/20 → 30。 */
    @Update("UPDATE t_pay_channel_flow SET flow_status = 30, channel_transaction_no = #{channelTxnNo}, "
            + "pay_time = #{payTime}, version = version + 1 "
            + "WHERE id = #{id} AND flow_status IN (10,20) AND deleted = 0")
    int markSuccess(@Param("id") Long id,
                    @Param("channelTxnNo") String channelTxnNo,
                    @Param("payTime") LocalDateTime payTime);

    /** 关单：10/20 → 50。 */
    @Update("UPDATE t_pay_channel_flow SET flow_status = 50, version = version + 1 "
            + "WHERE pay_no = #{payNo} AND flow_status IN (10,20) AND deleted = 0")
    int markClosedByPayNo(@Param("payNo") String payNo);

    /** 本行累计退款条件更新，不能超过本行实付。 */
    @Update("UPDATE t_pay_channel_flow SET paid_fen = paid_fen + #{amountFen}, version = version + 1 "
            + "WHERE id = #{id} AND paid_fen + #{amountFen} <= amount_fen AND deleted = 0")
    int addPaidFen(@Param("id") Long id, @Param("amountFen") long amountFen);

    @Select("SELECT * FROM t_pay_channel_flow WHERE pay_no = #{payNo} AND deleted = 0 ORDER BY id")
    List<ChannelFlow> selectByPayNo(@Param("payNo") String payNo);
}
