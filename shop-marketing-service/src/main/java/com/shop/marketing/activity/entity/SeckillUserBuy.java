package com.shop.marketing.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 秒杀每用户每场有效占件计数（R4-25：替代 V3 uk_activity_user 的硬「每用户一行」）。
 *
 * <p>同场次同用户唯一（uk_activity_user 迁移到本表），占件以条件更新
 * {@code total_qty + ? <= limit} 行锁原子完成，支持可配置 perUserBuyLimit 与多单累计；
 * 订单取消/超时释放时回减，口径等于原 SUM(t_seckill_order.qty WHERE status IN 0,1)。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_seckill_user_buy")
public class SeckillUserBuy extends BaseEntity {

    private Long activityId;
    private Long userId;
    /** 本场次仍有效（已锁定/已扣减）的累计件数 */
    private Integer totalQty;
}
