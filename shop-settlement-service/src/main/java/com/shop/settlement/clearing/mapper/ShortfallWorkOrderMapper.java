package com.shop.settlement.clearing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.clearing.entity.ShortfallWorkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 穿仓缺口工单 Mapper（P0-1）。uk_event_id 为数据库终态幂等，
 * reverse_no 在应用层先查后插（V5 DDL 仅普通列，补唯一键建议见实施报告）。
 */
@Mapper
public interface ShortfallWorkOrderMapper extends BaseMapper<ShortfallWorkOrder> {

    /** 按事件ID查单（uk_event_id）。 */
    @Select("SELECT * FROM t_sett_shortfall_workorder WHERE event_id = #{eventId} AND deleted = 0")
    ShortfallWorkOrder selectByEventId(@Param("eventId") String eventId);

    /** 按冲正流水号查单（实时事件与扫表补单跨路径去重）。 */
    @Select("SELECT * FROM t_sett_shortfall_workorder WHERE reverse_no = #{reverseNo} AND deleted = 0 "
            + "ORDER BY id ASC LIMIT 1")
    ShortfallWorkOrder selectByReverseNo(@Param("reverseNo") String reverseNo);

    /**
     * 商户未结清单（10/20），按时间序（id ASC）供保证金到账补扣。
     * 走 idx_merchant_status。
     */
    @Select("SELECT * FROM t_sett_shortfall_workorder WHERE merchant_id = #{merchantId} "
            + "AND status IN (10, 20) AND deleted = 0 ORDER BY id ASC")
    List<ShortfallWorkOrder> selectOpenByMerchant(@Param("merchantId") long merchantId);

    /**
     * 落单方获胜告警 CAS：10→20 且 alert_count+1。
     * @return 1 本次调用赢得告警权；0 已被其他路径处理
     */
    @Update("UPDATE t_sett_shortfall_workorder SET status = 20, alert_count = alert_count + 1, "
            + "update_time = NOW() WHERE id = #{id} AND status = 10 AND deleted = 0")
    int casAlerted(@Param("id") long id);

    /** 告警失败留痕（V5 表无 last_error 列，失败信息追加到 remark）。 */
    @Update("UPDATE t_sett_shortfall_workorder SET remark = #{remark}, update_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int updateRemark(@Param("id") long id, @Param("remark") String remark);

    /** 累加已补扣金额。 */
    @Update("UPDATE t_sett_shortfall_workorder SET clawed_back_fen = clawed_back_fen + #{amountFen}, "
            + "update_time = NOW() WHERE id = #{id} AND deleted = 0")
    int addClawedBack(@Param("id") long id, @Param("amountFen") long amountFen);

    /** 补满结清 CAS：扣足缺口且仍在 10/20 态 → 30。 */
    @Update("UPDATE t_sett_shortfall_workorder SET status = 30, update_time = NOW() "
            + "WHERE id = #{id} AND status IN (10, 20) AND deleted = 0 "
            + "AND clawed_back_fen >= shortfall_fen")
    int casClosed(@Param("id") long id);

    /**
     * 按状态键集分页（扫表补告警用，避免深分页）。
     * @param lastId 上一页最大 id，首页传 0
     */
    @Select("SELECT * FROM t_sett_shortfall_workorder WHERE status = #{status} AND deleted = 0 "
            + "AND id > #{lastId} ORDER BY id ASC LIMIT #{limit}")
    List<ShortfallWorkOrder> selectByStatusPaged(@Param("status") int status,
                                                 @Param("lastId") long lastId,
                                                 @Param("limit") int limit);
}
