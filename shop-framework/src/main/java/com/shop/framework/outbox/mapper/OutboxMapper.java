package com.shop.framework.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.framework.outbox.entity.OutboxMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * outbox mapper。框架包不在各服务 @Mapper 自动扫描根下，
 * 由 {@code OutboxConfig#mapperScannerConfigurer} 显式登记。
 */
@Mapper
public interface OutboxMapper extends BaseMapper<OutboxMessage> {

    /** 待投递 → 已投递（CAS；投递成功后调用，0 行说明已被别的路径置位）。 */
    @Update("UPDATE t_mq_outbox SET status = 1, update_time = NOW(3) WHERE id = #{id} AND status = 0")
    int markSent(@Param("id") Long id);

    /**
     * 记录一次投递失败：重试次数 +1；达到上限则置 2（挂起）并累计挂起次数，
     * 否则按指数退避重置 deliver_at。
     */
    @Update("UPDATE t_mq_outbox SET status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 2 ELSE 0 END, "
            + "suspend_count = suspend_count + CASE WHEN retry_count + 1 >= #{maxRetry} THEN 1 ELSE 0 END, "
            + "retry_count = retry_count + 1, last_error = #{error}, "
            + "deliver_at = DATE_ADD(NOW(3), INTERVAL LEAST(POWER(2, retry_count), 300) SECOND), "
            + "update_time = NOW(3) WHERE id = #{id} AND status = 0")
    int recordFailure(@Param("id") Long id, @Param("maxRetry") int maxRetry, @Param("error") String error);

    /** 挂起消息人工/对账重新放行（仅 2 → 0）。 */
    @Update("UPDATE t_mq_outbox SET status = 0, retry_count = 0, last_error = '', "
            + "deliver_at = NOW(3), update_time = NOW(3) WHERE id = #{id} AND status = 2")
    int requeue(@Param("id") Long id);

    /**
     * 慢车道自动重排：把冷却期已满、且累计挂起次数未超上限的挂起消息重新放回待投递。
     * 以挂起落库时间(update_time)作为冷却起点，避免 broker 刚恢复就立刻风暴重试；
     * suspend_count 达上限的真正死信保留 status=2，由扫描告警转人工/对账。
     *
     * @param cooldownSeconds 距上次挂起至少经过秒数
     * @param maxSuspend      允许自动重排的挂起次数上限（suspend_count &lt; 上限才放行）
     * @return 本轮放行条数
     */
    @Update("UPDATE t_mq_outbox SET status = 0, retry_count = 0, "
            + "deliver_at = NOW(3), update_time = NOW(3) "
            + "WHERE status = 2 AND suspend_count < #{maxSuspend} "
            + "AND update_time <= DATE_ADD(NOW(3), INTERVAL -#{cooldownSeconds} SECOND) "
            + "LIMIT 50")
    int requeueSuspended(@Param("cooldownSeconds") long cooldownSeconds,
                         @Param("maxSuspend") int maxSuspend);

    /** 超限仍挂起的死信条数（慢车道扫描后告警用）。 */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM t_mq_outbox WHERE status = 2 "
            + "AND suspend_count >= #{maxSuspend}")
    long countDead(@Param("maxSuspend") int maxSuspend);

    /** 本服务实际发出过的全部 topic（孤儿 topic 巡检发出侧清单）。 */
    @org.apache.ibatis.annotations.Select("SELECT DISTINCT topic FROM t_mq_outbox")
    List<String> selectDistinctTopics();

    /** O6：快车道积压（status=0 待投递全部，含未到期）。 */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM t_mq_outbox WHERE status = 0")
    long countPendingFast();

    /** O6：慢车道积压（status=2 挂起）。 */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM t_mq_outbox WHERE status = 2")
    long countPendingSlow();

    /** O6：已到期未投递消息的最老等待秒数（无到期消息时返回 null，Gauge 保留上次值）。 */
    @org.apache.ibatis.annotations.Select("SELECT TIMESTAMPDIFF(SECOND, MIN(deliver_at), NOW(3)) "
            + "FROM t_mq_outbox WHERE status = 0 AND deliver_at <= NOW(3)")
    Long oldestFastAgeSeconds();

    /** O6：挂起消息的最老停留秒数（以 update_time 为挂起起点；无挂起返回 null）。 */
    @org.apache.ibatis.annotations.Select("SELECT TIMESTAMPDIFF(SECOND, MIN(update_time), NOW(3)) "
            + "FROM t_mq_outbox WHERE status = 2")
    Long oldestSlowAgeSeconds();
}
