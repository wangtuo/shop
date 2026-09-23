package com.shop.pay.feature.recon.support;

import com.shop.api.pay.enums.ReconcileDiffTypes;
import com.shop.pay.channel.ChannelBillRecord;
import com.shop.pay.feature.payment.entity.Payment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T+1 对账纯规则比对（design 6.5）：
 * <ul>
 *   <li>长款：渠道账单有、本地无；</li>
 *   <li>短款：本地支付成功、渠道账单无；</li>
 *   <li>金额不符：两边都有但金额不一致（以渠道为准）。</li>
 * </ul>
 */
@Component
public class ReconcileMatcher {

    /**
     * @param localByPayNo  本地成功支付单（已按渠道过滤），key=payNo
     * @param channelBills  渠道成功账单
     * @return 差错草稿（可能为空，表示账实相符）
     */
    public List<ReconDiffDraft> match(Map<String, Payment> localByPayNo,
                                      List<ChannelBillRecord> channelBills) {
        List<ReconDiffDraft> diffs = new ArrayList<>();
        Set<String> channelMatched = new HashSet<>();

        for (ChannelBillRecord bill : safe(channelBills)) {
            if (!bill.isSuccess()) {
                continue;
            }
            Payment local = localByPayNo == null ? null : localByPayNo.get(bill.getPayNo());
            if (local == null) {
                // 长款：渠道有、本地无
                diffs.add(ReconDiffDraft.builder()
                        .diffType(ReconcileDiffTypes.LONG.getCode())
                        .payNo(bill.getPayNo())
                        .orderNo(bill.getOrderNo())
                        .channelTxnNo(bill.getChannelTxnNo())
                        .localAmountFen(0L)
                        .channelAmountFen(nz(bill.getAmountFen()))
                        .build());
                continue;
            }
            channelMatched.add(bill.getPayNo());
            if (!nz(local.getAmountFen()).equals(nz(bill.getAmountFen()))) {
                // 金额不符
                diffs.add(ReconDiffDraft.builder()
                        .diffType(ReconcileDiffTypes.AMOUNT_MISMATCH.getCode())
                        .payNo(local.getPayNo())
                        .orderNo(local.getOrderNo())
                        .channelTxnNo(bill.getChannelTxnNo())
                        .localAmountFen(nz(local.getAmountFen()))
                        .channelAmountFen(nz(bill.getAmountFen()))
                        .build());
            }
        }

        if (localByPayNo != null) {
            for (Payment local : localByPayNo.values()) {
                if (!channelMatched.contains(local.getPayNo())) {
                    // 短款：本地有、渠道无
                    diffs.add(ReconDiffDraft.builder()
                            .diffType(ReconcileDiffTypes.SHORT.getCode())
                            .payNo(local.getPayNo())
                            .orderNo(local.getOrderNo())
                            .localAmountFen(nz(local.getAmountFen()))
                            .channelAmountFen(0L)
                            .build());
                }
            }
        }
        return diffs;
    }

    private List<ChannelBillRecord> safe(List<ChannelBillRecord> bills) {
        return bills == null ? List.of() : bills;
    }

    private Long nz(Long v) {
        return v == null ? 0L : v;
    }
}
