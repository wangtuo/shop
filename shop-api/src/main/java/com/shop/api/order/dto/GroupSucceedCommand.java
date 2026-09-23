package com.shop.api.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 拼团成团标记命令（MARKETING C25，TRADE 半卡）。
 *
 * <p>已付款单直接 10 → 20 待发货并发既有履约事件；未付款单挂“已成团”标记，等支付回调自然进 20。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupSucceedCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单号 */
    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;

    /** 团号 */
    @NotBlank(message = "groupNo 不能为空")
    private String groupNo;
}
