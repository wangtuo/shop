package com.shop.pay.support;

import com.shop.api.pay.dto.CreatePaymentCommand;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.pay.dto.RefundDTO;
import com.shop.pay.feature.payment.entity.Payment;
import com.shop.pay.feature.refund.entity.RefundOrder;

/**
 * 实体 → 对外契约 DTO 装配。
 */
public final class PayAssembler {

    private PayAssembler() {
    }

    public static PaymentDTO toPaymentDTO(Payment p) {
        if (p == null) {
            return null;
        }
        return PaymentDTO.builder()
                .payNo(p.getPayNo())
                .orderNo(p.getOrderNo())
                .userId(p.getUserId())
                .payMethod(p.getPayMethod())
                .amountFen(p.getAmountFen())
                .status(p.getStatus())
                .payUrl(p.getPayUrl())
                .channelTransactionNo(p.getChannelTransactionNo())
                .createTime(p.getCreateTime())
                .payTime(p.getPayTime())
                .build();
    }

    public static RefundDTO toRefundDTO(RefundOrder r) {
        if (r == null) {
            return null;
        }
        return RefundDTO.builder()
                .refundNo(r.getRefundNo())
                .payNo(r.getPayNo())
                .orderNo(r.getOrderNo())
                .aftersaleNo(r.getAftersaleNo())
                .userId(r.getUserId())
                .amountFen(r.getAmountFen())
                .payMethod(r.getPayMethod())
                .refundType(r.getRefundType())
                .status(r.getStatus())
                .createTime(r.getCreateTime())
                .finishTime(r.getFinishTime())
                .build();
    }

    public static CreatePaymentCommand singleCommand(String orderNo, Long userId, Integer payMethod,
                                                     Long amountFen, String subject, Integer terminal) {
        return CreatePaymentCommand.builder()
                .orderNo(orderNo)
                .userId(userId)
                .payMethod(payMethod)
                .amountFen(amountFen)
                .subject(subject)
                .terminal(terminal)
                .build();
    }
}
