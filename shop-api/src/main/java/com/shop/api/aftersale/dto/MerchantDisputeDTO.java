package com.shop.api.aftersale.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 商户未终结售后/介入单存在性查询结果（FUNDS C8，商户保证金判据）。
 *
 * <p>判据：该商户在 since 之后存在未终结售后/介入单
 * （t_aftersale_order.status ∉ {50,55,90} 或 t_aftersale_dispute.status ≠ 30）即 exists=true。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantDisputeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否存在未终结售后/介入单 */
    private Boolean exists;

    /** 未终结单据数量 */
    private Long openCount;

    /** since 之后近期已终结单据数量 */
    private Long recentFinishedCount;
}
