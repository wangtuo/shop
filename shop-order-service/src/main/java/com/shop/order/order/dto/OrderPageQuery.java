package com.shop.order.order.dto;

import com.shop.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 订单分页查询（用户端/商户端共用，归属过滤由 Service 强制追加）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderPageQuery extends PageQuery {

    /** 订单状态 */
    private Integer status;
    /** 订单类型 */
    private Integer orderType;
    /** 起始时间 */
    private LocalDateTime startTime;
    /** 截止时间 */
    private LocalDateTime endTime;
}
