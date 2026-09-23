package com.shop.pay.feature.recon.support;

import com.shop.api.pay.enums.ReconcileDiffTypes;
import com.shop.pay.channel.ChannelBillRecord;
import com.shop.pay.feature.payment.entity.Payment;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T+1 对账三类差错识别（design 6.5）：长款 / 短款 / 金额不符。
 */
class ReconcileMatcherTest {

    private final ReconcileMatcher matcher = new ReconcileMatcher();

    private Payment payment(String payNo, long amountFen) {
        Payment p = new Payment();
        p.setPayNo(payNo);
        p.setOrderNo("O" + payNo);
        p.setAmountFen(amountFen);
        return p;
    }

    private ChannelBillRecord bill(String payNo, String txnNo, long amountFen, boolean success) {
        return ChannelBillRecord.builder()
                .channelCode("MOCK_WECHAT")
                .payNo(payNo)
                .orderNo(payNo == null ? null : "O" + payNo)
                .channelTxnNo(txnNo)
                .amountFen(amountFen)
                .success(success)
                .build();
    }

    @Test
    void match_长款短款金额不符_全部识别() {
        Map<String, Payment> local = new HashMap<>();
        local.put("P1", payment("P1", 100));  // 完全匹配
        local.put("P2", payment("P2", 200));  // 金额不符（渠道 210）
        local.put("P4", payment("P4", 300));  // 短款（渠道无）

        List<ChannelBillRecord> bills = List.of(
                bill("P1", "T1", 100, true),
                bill("P2", "T2", 210, true),
                bill("P3", "T3", 50, true)    // 长款（本地无）
        );

        List<ReconDiffDraft> diffs = matcher.match(local, bills);

        assertEquals(3, diffs.size());
        Map<String, ReconDiffDraft> byPayNo = new HashMap<>();
        for (ReconDiffDraft d : diffs) {
            byPayNo.put(d.getPayNo(), d);
        }
        assertEquals(ReconcileDiffTypes.LONG.getCode(), byPayNo.get("P3").getDiffType());
        assertEquals(50, byPayNo.get("P3").getChannelAmountFen());
        assertEquals(0, byPayNo.get("P3").getLocalAmountFen());

        assertEquals(ReconcileDiffTypes.AMOUNT_MISMATCH.getCode(), byPayNo.get("P2").getDiffType());
        assertEquals(200, byPayNo.get("P2").getLocalAmountFen());
        assertEquals(210, byPayNo.get("P2").getChannelAmountFen());

        assertEquals(ReconcileDiffTypes.SHORT.getCode(), byPayNo.get("P4").getDiffType());
        assertEquals(300, byPayNo.get("P4").getLocalAmountFen());
    }

    @Test
    void match_账实相符_无差错() {
        Map<String, Payment> local = Map.of("P1", payment("P1", 100));
        List<ChannelBillRecord> bills = List.of(bill("P1", "T1", 100, true));
        assertTrue(matcher.match(local, bills).isEmpty());
    }

    @Test
    void match_渠道失败账单_不产生长款() {
        Map<String, Payment> local = Map.of("P1", payment("P1", 100));
        // 渠道有失败账单：既不算匹配成功，也不应产生长款
        List<ChannelBillRecord> bills = List.of(bill("P1", "T1", 100, false));
        List<ReconDiffDraft> diffs = matcher.match(local, bills);
        assertEquals(1, diffs.size());
        assertEquals(ReconcileDiffTypes.SHORT.getCode(), diffs.get(0).getDiffType());
    }

    @Test
    void match_空账单_全部本地单记短款() {
        Map<String, Payment> local = Map.of("P1", payment("P1", 100), "P2", payment("P2", 200));
        List<ReconDiffDraft> diffs = matcher.match(local, List.of());
        assertEquals(2, diffs.size());
        assertTrue(diffs.stream().allMatch(d -> d.getDiffType() == ReconcileDiffTypes.SHORT.getCode()));
    }
}
