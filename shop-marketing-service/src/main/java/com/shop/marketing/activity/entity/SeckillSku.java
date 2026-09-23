package com.shop.marketing.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 秒杀 SKU 库存（DB 库存，与 Redis 原子库存对账）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_seckill_sku")
public class SeckillSku extends BaseEntity {

    private Long activityId;
    private Long skuId;
    private Long seckillPriceFen;
    private Integer totalStock;
    private Integer lockedStock;
    private Integer soldStock;
    private Integer version;
}
