package com.shop.order.mq.message;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 订单域内部延时消息体（支付超时 / 自动收货 / 售后期结束三个 Topic 共用）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class OrderDelayMessage extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 订单号 */
    private String orderNo;
}
