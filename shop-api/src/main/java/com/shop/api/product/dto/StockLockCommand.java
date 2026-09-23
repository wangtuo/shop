package com.shop.api.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import com.shop.common.constant.BatchSizes;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * TCC-try 库存锁定命令：可售库存 → 锁定库存（design 3.3.2）。
 *
 * <p>幂等键：orderNo + items。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockLockCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单号（幂等键） */
    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;

    /** 订单类型：1 普通 2 秒杀 3 拼团 4 预售 5 换货 */
    private Integer orderType;

    /** 锁定明细 */
    @NotEmpty(message = "items 不能为空")
    @Size(min = 1, max = BatchSizes.ITEMS_MAX, message = "items 条数必须在 1-" + BatchSizes.ITEMS_MAX + " 之间")
    @Valid
    @Builder.Default
    private List<StockItemCommand> items = new ArrayList<>();
}
