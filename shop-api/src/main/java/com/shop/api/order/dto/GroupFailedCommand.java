package com.shop.api.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 拼团失败关单命令（MARKETING C25，TRADE 半卡）。
 *
 * <p>未付款单按超时取消关单；已付款单触发原路退款编排——退款由 order 域经 PayClient.refund
 * 发起（refundNo 幂等），marketing 不直接碰 PayClient。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupFailedCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单号 */
    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;

    /** 团号 */
    @NotBlank(message = "groupNo 不能为空")
    private String groupNo;
}
