package com.shop.order.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单明细实体（t_order_item，design 5.1.1 OrderItem）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order_item")
public class OrderItem extends BaseEntity {

    private String orderNo;
    private Long userId;
    private Long skuId;
    private Long spuId;
    private Long merchantId;
    private Long shopId;
    private Long category3Id;

    private String skuName;
    private String specText;
    private String image;

    /** 成交单价（分） */
    private Long priceFen;
    private Integer qty;
    private Long itemTotalFen;
    private Long discountAllocFen;
    private Long pointsAllocFen;
    private Long freightAllocFen;
    /** 明细实付（分，售后退款基数） */
    private Long paidFen;

    /** 0 无 1 申请中 2 退款中 3 已退款 4 退货中 5 换货中 6 已完成 */
    private Integer aftersaleStatus;
    /** 累计退款金额（分） */
    private Long refundedFen;
    /** 当前关联售后单号 */
    private String aftersaleNo;

    /** 库存类型 1 普通 2 预售 3 秒杀 4 拼团 */
    private Integer stockType;
    private Long activityId;
    private Long seckillActivityId;
    private String groupNo;
    private Long presaleActivityId;

    @Version
    private Integer version;
}
