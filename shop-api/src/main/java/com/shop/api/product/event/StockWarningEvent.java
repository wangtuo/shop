package com.shop.api.product.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 库存预警事件，Topic：{@code MqTopics.STOCK_WARNING}（CONTRACTS.md §5）。
 *
 * <p>触发条件（design 3.3.3）：当 SKU 可售库存 ≤ 预警阈值（默认 10）时发送，
 * 消费者落预警表并日志告警；库存为 0 时商品自动下架（售罄），补货后自动上架。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class StockWarningEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** SKU ID */
    private Long skuId;

    /** SPU ID */
    private Long spuId;

    /** 商家 ID */
    private Long merchantId;

    /** 触发时的可售库存 */
    private Long available;

    /** 预警阈值 */
    private Long threshold;
}
