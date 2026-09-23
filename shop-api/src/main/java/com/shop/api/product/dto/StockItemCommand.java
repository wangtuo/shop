package com.shop.api.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单笔库存操作明细。可用于普通、预售、秒杀、拼团等不同库存类型。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockItemCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SKU ID */
    @NotNull(message = "skuId 不能为空")
    private Long skuId;

    /** 操作数量，必须为正整数 */
    @NotNull(message = "qty 不能为空")
    @Min(value = 1, message = "qty 必须大于等于 1")
    private Integer qty;

    /** 库存类型：1 普通 2 预售 3 秒杀 4 拼团，缺省按普通库存处理 */
    private Integer stockType;

    /** 关联活动 ID（秒杀/拼团/预售活动），普通订单为空 */
    private Long activityId;
}
