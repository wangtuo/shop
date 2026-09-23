package com.shop.marketing.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 秒杀订单记录：0已锁定 1已扣减 2已释放。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_seckill_order")
public class SeckillOrder extends BaseEntity {

    private Long activityId;
    private Long skuId;
    private Long userId;
    private String orderNo;
    private Integer qty;
    private Integer status;
}
