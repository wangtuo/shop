package com.shop.api.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 售后回库命令。
 *
 * <p>回库规则（CONTRACTS.md §5 AFTERSALE_CHANGED 链路）：</p>
 * <ul>
 *     <li>买家责任/换货（非质量问题）：退回可售库存；</li>
 *     <li>质量问题：不入可售库存，入残次品库。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockReturnCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单号（幂等键） */
    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;

    /** 回库明细 */
    @NotEmpty(message = "items 不能为空")
    @Valid
    @Builder.Default
    private List<StockItemCommand> items = new ArrayList<>();

    /** 回库原因，取值见 StockReturnReasons（1 买家责任 2 质量问题 3 换货） */
    private Integer reason;
}
