package com.shop.pay.mq.message;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 支付状态主动查询延时消息（补偿双保险：RocketMQ 延时 + 定时扫描）。
 * Topic：MqTopics.PAY_RESULT，Tag：check。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PayCheckMessage extends BaseEvent {

    private String payNo;

    public PayCheckMessage(String payNo, String bizNo) {
        this.payNo = payNo;
        setBizNo(bizNo);
    }
}
