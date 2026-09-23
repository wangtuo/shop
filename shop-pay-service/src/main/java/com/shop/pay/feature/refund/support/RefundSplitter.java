package com.shop.pay.feature.refund.support;

import com.shop.common.util.MoneyUtils;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 混合支付退款拆算（design 6.4.1）：按各支付手段"当前可退金额"（实付−已退）为权重，
 * 用 {@link MoneyUtils#allocate} 最大余数法拆分，各分行合计严格等于本次退款总额，不差 1 分。
 */
@Component
public class RefundSplitter {

    /**
     * @param refundFen 本次退款总额（分），调用方保证 ≤ 各流水可退之和
     * @param flows     原支付渠道流水（含每行实付/已退）
     * @return 与 flows 等长的拆分明细金额
     */
    public List<Long> split(long refundFen, List<ChannelFlow> flows) {
        if (refundFen <= 0) {
            throw new IllegalArgumentException("退款金额必须大于0");
        }
        if (flows == null || flows.isEmpty()) {
            throw new IllegalArgumentException("支付渠道流水为空，无法原路退款");
        }
        List<Long> available = flows.stream()
                .map(f -> Math.max(0L, f.getAmountFen() - nz(f.getPaidFen())))
                .toList();
        long availableSum = MoneyUtils.sum(available);
        if (availableSum < refundFen) {
            throw new IllegalArgumentException("可退金额不足: 申请=" + refundFen + " 可退=" + availableSum);
        }
        return MoneyUtils.allocate(refundFen, available);
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }
}
