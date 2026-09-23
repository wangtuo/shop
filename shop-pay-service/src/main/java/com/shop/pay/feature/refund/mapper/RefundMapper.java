package com.shop.pay.feature.refund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.pay.feature.refund.entity.RefundOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RefundMapper extends BaseMapper<RefundOrder> {

    /** 10 → 20 退款中。 */
    @Update("UPDATE t_pay_refund SET status = 20, version = version + 1 "
            + "WHERE refund_no = #{refundNo} AND status = 10 AND deleted = 0")
    int markProcessing(@Param("refundNo") String refundNo);

    /** 20 → 30 成功。 */
    @Update("UPDATE t_pay_refund SET status = 30, finish_time = #{finishTime}, version = version + 1 "
            + "WHERE refund_no = #{refundNo} AND status IN (10,20) AND deleted = 0")
    int markSuccess(@Param("refundNo") String refundNo, @Param("finishTime") LocalDateTime finishTime);

    /** → 40 失败，累计重试次数。 */
    @Update("UPDATE t_pay_refund SET status = 40, fail_reason = #{reason}, "
            + "retry_count = retry_count + #{incr}, version = version + 1 "
            + "WHERE refund_no = #{refundNo} AND status IN (10,20,40) AND deleted = 0")
    int markFail(@Param("refundNo") String refundNo,
                 @Param("reason") String reason,
                 @Param("incr") int incr);

    /** 失败重试：40 → 10 待退款。 */
    @Update("UPDATE t_pay_refund SET status = 10, fail_reason = NULL, version = version + 1 "
            + "WHERE refund_no = #{refundNo} AND status = 40 AND deleted = 0")
    int reopen(@Param("refundNo") String refundNo);

    /** → 50 已冲正（清算冲正回滚）。 */
    @Update("UPDATE t_pay_refund SET status = 50, finish_time = #{finishTime}, version = version + 1 "
            + "WHERE refund_no = #{refundNo} AND status IN (30,40) AND deleted = 0")
    int markReversed(@Param("refundNo") String refundNo, @Param("finishTime") LocalDateTime finishTime);

    /**
     * B8：捞取需主动查询渠道的 PROCESSING(20) 退款单：
     * 从未查询且创建已超 grace（避免刚发起与同步路径竞争），或上次查询早于 staleBefore。
     */
    @Select("SELECT * FROM t_pay_refund WHERE status = 20 AND deleted = 0 AND ("
            + "(last_query_time IS NULL AND create_time <= #{createdBefore}) "
            + "OR last_query_time < #{staleBefore}) ORDER BY id LIMIT #{limit}")
    List<RefundOrder> selectProcessingForQuery(@Param("createdBefore") LocalDateTime createdBefore,
                                               @Param("staleBefore") LocalDateTime staleBefore,
                                               @Param("limit") int limit);

    /**
     * B8：查询占位 CAS：仅仍为 20 且到查询间隔的单更新成功（last_query_time=now、query_count+1），
     * 返回 1 行的节点才真正调渠道，防止多节点/多轮次重复查询。
     */
    @Update("UPDATE t_pay_refund SET last_query_time = #{now}, query_count = query_count + 1, "
            + "version = version + 1 WHERE id = #{id} AND status = 20 AND deleted = 0 "
            + "AND (last_query_time IS NULL OR last_query_time <= #{staleBefore})")
    int touchQuery(@Param("id") Long id,
                   @Param("staleBefore") LocalDateTime staleBefore,
                   @Param("now") LocalDateTime now);
}
