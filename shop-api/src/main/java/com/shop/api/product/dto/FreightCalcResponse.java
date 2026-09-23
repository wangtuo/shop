package com.shop.api.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 运费试算响应（TRADE C32）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreightCalcResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 应付运费（分） */
    private Long freightFen;

    /** 命中运费规则快照 JSON（随订单价格快照落库，事后可追溯） */
    private String snapshotJson;
}
