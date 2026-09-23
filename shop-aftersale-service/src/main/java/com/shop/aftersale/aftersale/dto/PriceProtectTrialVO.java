package com.shop.aftersale.aftersale.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 价保试算结果。
 */
@Data
@Builder
public class PriceProtectTrialVO implements Serializable {
    private String orderNo;
    private Long orderItemId;
    private Long skuId;
    private Long originalUnitFen;
    private Long currentPriceFen;
    private Long diffUnitFen;
    private Integer qty;
    private Long diffTotalFen;
    private boolean eligible;
    private String reason;
}
