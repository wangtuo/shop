package com.shop.product.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存预警记录（消费/落库 STOCK_WARNING）。只追加，不做逻辑删除。
 */
@Data
@TableName("t_product_stock_warning")
public class StockWarning implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** SKU ID */
    private Long skuId;

    /** SPU ID */
    private Long spuId;

    /** 商家 ID */
    private Long merchantId;

    /** 触发时可售库存 */
    private Long available;

    /** 预警阈值 */
    private Long threshold;

    /** 预警时间 */
    private LocalDateTime createTime;
}
