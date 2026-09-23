package com.shop.api.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单状态批量查询结果行（TRADE C33）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatusDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单状态：见 OrderStatuses（10 待付款 … 70 已关闭） */
    private Integer status;

    /** 订单类型：1 普通 2 秒杀 3 拼团 4 预售 5 换货 */
    private Integer orderType;

    /** 预售尾款阶段标记（非预售为 null） */
    private Boolean presaleFinalStage;

    /** 订单创建时间 */
    private LocalDateTime gmtCreate;
}
