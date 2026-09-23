package com.shop.product.mq;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MQ 消费流水 Mapper：INSERT IGNORE 做 event_id 幂等。
 */
@Mapper
public interface MqConsumeRecordMapper {

    /**
     * 尝试登记消费记录。
     *
     * <p>t_product_mq_consume.id 为「雪花 ID」列（非 AUTO_INCREMENT，见 DDL），
     * 必须显式赋值；历史上漏传 id 时，INSERT IGNORE 在 STRICT 模式下首行以隐式 0
     * 落库，此后每条都因主键 0 冲突被 IGNORE 成 0 行，{@code event_id} 幂等判定
     * 退化为「永远重复」，导致 ORDER_PAID 库存 confirm 等全部消费逻辑被静默跳过。
     *
     * @return 1 首次插入成功；0 eventId 已存在（重复投递，直接 ACK）
     */
    @Insert("INSERT IGNORE INTO t_product_mq_consume (id, event_id, topic, biz_no, status, create_time) "
            + "VALUES (#{id}, #{eventId}, #{topic}, #{bizNo}, 1, NOW())")
    int tryInsert(@Param("id") Long id,
                  @Param("eventId") String eventId,
                  @Param("topic") String topic,
                  @Param("bizNo") String bizNo);
}
