package com.shop.api.order.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

/**
 * 订单事件中的商品明细消息体（CONTRACTS.md §5 ORDER_* 事件 items 元素）。
 *
 * <p>营销活动字段按订单类型填充：秒杀填 {@link #seckillActivityId}，
 * 拼团填 {@link #groupNo}，预售填 {@link #presaleActivityId}，其他类型为 null。
 */
@Data
@ToString(callSuper = false)
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SKU ID */
    private Long skuId;

    /** SPU ID */
    private Long spuId;

    /** 商户 ID */
    private Long merchantId;

    /** 店铺 ID */
    private Long shopId;

    /** 三级类目 ID（营销锁定/对账使用） */
    private Long category3Id;

    /** 购买数量 */
    private Integer qty;

    /** 成交单价（分） */
    private Long salePriceFen;

    /** 商品总额：单价 × 数量（分） */
    private Long productTotalFen;

    /** 明细实付金额（分） */
    private Long paidFen;

    /** 秒杀活动 ID（秒杀订单） */
    private Long seckillActivityId;

    /** 拼团团号（拼团订单） */
    private String groupNo;

    /** 拼团活动 ID（与 groupNo 并列，营销半卡按活动 ID 取团长价/资源） */
    private Long groupbuyActivityId;

    /** 预售活动 ID（预售订单） */
    private Long presaleActivityId;
}
