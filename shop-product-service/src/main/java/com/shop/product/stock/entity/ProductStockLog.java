package com.shop.product.stock.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 库存 TCC 单据流水。唯一约束 uk(order_no, sku_id, type) 保证同一单据同一 SKU
 * 同一库存类型只有一条流水，状态在该流水上推进：
 * 0 锁定中 → 1 已扣减(入占用) → 3 已回库 / 4 已出账(发货)；
 * 0 → 2 已释放；预售定金流水 1 → 5 预售回补（尾款违约）。
 * 预售尾款流水 status=1 且 {@code refOrderNo} 指向定金单号（B5）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product_stock_log")
public class ProductStockLog extends BaseEntity {

    /** 业务订单号（幂等键） */
    private String orderNo;

    /** 关联单号（尾款流水回指定金单号，B5） */
    private String refOrderNo;

    /** SKU ID */
    private Long skuId;

    /** SPU ID（冗余） */
    private Long spuId;

    /** 商家 ID（冗余） */
    private Long merchantId;

    /** 库存类型：1 普通 2 预售 3 秒杀 4 拼团 */
    private Integer type;

    /** 操作数量 */
    private Integer qty;

    /** 流水状态：0 锁定中 1 已扣减 2 已释放 3 已回库 4 已出账 5 预售回补 */
    private Integer status;

    /** 回库原因：1 买家责任 2 质量问题 3 换货 */
    private Integer returnReason;
}
