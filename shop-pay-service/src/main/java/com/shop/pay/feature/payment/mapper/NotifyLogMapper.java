package com.shop.pay.feature.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.pay.feature.payment.entity.NotifyLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotifyLogMapper extends BaseMapper<NotifyLog> {

    /** 回调通知幂等落库：同渠道同 notifyId 重复通知直接忽略（返回 0 行）。 */
    @Insert("INSERT IGNORE INTO t_pay_notify_log "
            + "(channel_code, notify_id, pay_no, refund_no, channel_txn_no, notify_type, sign_status, "
            + "handle_status, notify_body, fail_reason, deleted, create_time, update_time) "
            + "VALUES (#{channelCode}, #{notifyId}, #{payNo}, #{refundNo}, #{channelTxnNo}, #{notifyType}, "
            + "#{signStatus}, #{handleStatus}, #{notifyBody}, #{failReason}, 0, NOW(), NOW())")
    int insertIgnore(NotifyLog log);

    @Update("UPDATE t_pay_notify_log SET sign_status = #{signStatus}, handle_status = #{handleStatus}, "
            + "fail_reason = #{failReason} WHERE id = #{id} AND deleted = 0")
    int updateResult(@Param("id") Long id,
                     @Param("signStatus") int signStatus,
                     @Param("handleStatus") int handleStatus,
                     @Param("failReason") String failReason);
}
