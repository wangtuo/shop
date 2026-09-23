package com.shop.pay.feature.refund.support;

import com.shop.pay.feature.payment.entity.ChannelFlow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混合支付退款拆分守恒测试（design 6.4.1，最大余数法不差 1 分）。
 */
class RefundSplitterTest {

    private final RefundSplitter splitter = new RefundSplitter();

    private ChannelFlow flow(long amount, long paid) {
        ChannelFlow f = new ChannelFlow();
        f.setId((long) (Math.random() * 10000));
        f.setChannelCode("MOCK_WECHAT");
        f.setAmountFen(amount);
        f.setPaidFen(paid);
        return f;
    }

    @Test
    void split_六四比例_合计守恒() {
        List<ChannelFlow> flows = List.of(flow(6000, 0), flow(4000, 0));
        List<Long> parts = splitter.split(1000, flows);
        assertEquals(2, parts.size());
        assertEquals(1000, parts.get(0) + parts.get(1));
        assertEquals(600, parts.get(0));
        assertEquals(400, parts.get(1));
    }

    @Test
    void split_除不尽_尾差不超过1且守恒() {
        // 实付 1:1:1，退 100 分 → 34/33/33，合计必须 100
        List<ChannelFlow> flows = List.of(flow(5000, 0), flow(5000, 0), flow(5000, 0));
        List<Long> parts = splitter.split(100, flows);
        assertEquals(100L, parts.stream().mapToLong(Long::longValue).sum());
        long max = parts.stream().mapToLong(Long::longValue).max().orElseThrow();
        long min = parts.stream().mapToLong(Long::longValue).min().orElseThrow();
        assertTrue(max - min <= 1, "最大余数法各份之差不超过1分");
    }

    @Test
    void split_多次部分退款_按当前可退金额比例拆分() {
        // 已退 300/200（比例一致），再退 500：可退 5700/3800
        List<ChannelFlow> flows = List.of(flow(6000, 300), flow(4000, 200));
        List<Long> parts = splitter.split(500, flows);
        assertEquals(500, parts.get(0) + parts.get(1));
        assertEquals(300, parts.get(0));
        assertEquals(200, parts.get(1));
        // 每一份都不超过本行可退
        assertTrue(parts.get(0) <= 5700);
        assertTrue(parts.get(1) <= 3800);
    }

    @Test
    void split_全额退完最后1分_守恒() {
        List<ChannelFlow> flows = List.of(flow(1, 0), flow(2, 0));
        List<Long> parts = splitter.split(3, flows);
        assertEquals(3, parts.get(0) + parts.get(1));
    }

    @Test
    void split_超过可退总额_抛异常() {
        List<ChannelFlow> flows = List.of(flow(600, 100), flow(400, 100));
        // 可退 800，申请 801
        assertThrows(IllegalArgumentException.class, () -> splitter.split(801, flows));
    }

    @Test
    void split_部分流水已退尽_尾差只分给仍有可退余额的流水() {
        // 第一行已全额退完（可退权重 0），100 分只能全部退到第二行；
        // 最大余数法不得把 +1 尾差分给权重 0 的行
        List<ChannelFlow> flows = List.of(
                flowCode("MOCK_WECHAT", 6000, 6000),
                flowCode("MOCK_ALIPAY", 4000, 0));
        List<Long> parts = splitter.split(100, flows);
        assertEquals(2, parts.size());
        assertEquals(0L, parts.get(0), "已退尽流水不再分到退款");
        assertEquals(100L, parts.get(1));
        assertEquals(100L, parts.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void split_退1分跨三行_最大余数法仅最大权重行得分() {
        // 权重 5:3:2，退 1 分：整除份额全 0，余数 5>3>2，尾差落第一行
        List<ChannelFlow> flows = List.of(
                flowCode("MOCK_WECHAT", 5000, 0),
                flowCode("MOCK_ALIPAY", 3000, 0),
                flowCode("BALANCE", 2000, 0));
        List<Long> parts = splitter.split(1, flows);
        assertEquals(List.of(1L, 0L, 0L), parts);
    }

    @Test
    void split_除不尽多行_最大余数法守恒且单份不超本行可退() {
        // 100 按 1:1:1 → 34/33/33；再验证任意份额 ≤ 本行可退
        List<ChannelFlow> flows = List.of(
                flowCode("MOCK_WECHAT", 100, 0),
                flowCode("MOCK_ALIPAY", 100, 0),
                flowCode("BALANCE", 100, 0));
        List<Long> parts = splitter.split(100, flows);
        assertEquals(100L, parts.stream().mapToLong(Long::longValue).sum());
        for (int i = 0; i < flows.size(); i++) {
            long available = flows.get(i).getAmountFen() - flows.get(i).getPaidFen();
            assertTrue(parts.get(i) <= available, "拆份额不得超过本行可退: " + parts.get(i));
        }
    }

    @Test
    void split_全部流水已退尽_无可退金额抛异常() {
        List<ChannelFlow> flows = List.of(
                flowCode("MOCK_WECHAT", 6000, 6000),
                flowCode("MOCK_ALIPAY", 4000, 4000));
        assertThrows(IllegalArgumentException.class, () -> splitter.split(1, flows));
    }

    @Test
    void split_余额加在线混合_按当前可退比例退回且守恒() {
        // 余额 3000 + 微信 7000，全额退 10000：3000/7000 精确无尾差
        List<ChannelFlow> flows = List.of(
                flowCode("BALANCE", 3000, 0),
                flowCode("MOCK_WECHAT", 7000, 0));
        List<Long> parts = splitter.split(10000, flows);
        assertEquals(List.of(3000L, 7000L), parts);
    }

    private ChannelFlow flowCode(String code, long amount, long paid) {
        ChannelFlow f = new ChannelFlow();
        f.setId((long) (Math.random() * 10000));
        f.setChannelCode(code);
        f.setAmountFen(amount);
        f.setPaidFen(paid);
        return f;
    }
}
