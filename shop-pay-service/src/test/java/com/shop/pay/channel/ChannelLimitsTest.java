package com.shop.pay.channel;

import com.shop.api.pay.enums.PayMethods;
import com.shop.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 各渠道限额与终端校验（design 6.1）。
 */
class ChannelLimitsTest {

    @Test
    void check_限额边界_通过() {
        assertDoesNotThrow(() -> ChannelLimits.check(PayMethods.WECHAT, ChannelLimits.LIMIT_5W, 1));
        assertDoesNotThrow(() -> ChannelLimits.check(PayMethods.ALIPAY, 1L, 4));
        assertDoesNotThrow(() -> ChannelLimits.check(PayMethods.BANK_CARD, ChannelLimits.LIMIT_BANK, 2));
    }

    @Test
    void check_超出限额_抛支付异常() {
        BizException ex = assertThrows(BizException.class,
                () -> ChannelLimits.check(PayMethods.WECHAT, ChannelLimits.LIMIT_5W + 1, 1));
        assertEquals(60001, ex.getCode());

        assertThrows(BizException.class,
                () -> ChannelLimits.check(PayMethods.BANK_CARD, ChannelLimits.LIMIT_BANK + 1, 1));
        assertThrows(BizException.class,
                () -> ChannelLimits.check(PayMethods.BAITIAO, ChannelLimits.LIMIT_CREDIT + 1, 1));
    }

    @Test
    void check_余额无固定限额_任意金额通过() {
        assertDoesNotThrow(() -> ChannelLimits.check(PayMethods.BALANCE, 99_999_999L, 4));
        assertTrue(ChannelLimits.isBalance(ChannelLimits.channelCode(PayMethods.BALANCE)));
    }

    @Test
    void check_终端不支持_抛参数异常() {
        // 银行卡/云闪付仅 APP/H5，PC(4) 不支持
        assertThrows(IllegalArgumentException.class,
                () -> ChannelLimits.check(PayMethods.BANK_CARD, 100L, 4));
        assertThrows(IllegalArgumentException.class,
                () -> ChannelLimits.check(PayMethods.UNIONPAY, 100L, 3));
        // 花呗/白条仅 APP
        assertThrows(IllegalArgumentException.class,
                () -> ChannelLimits.check(PayMethods.HUABEI, 100L, 2));
        assertThrows(IllegalArgumentException.class,
                () -> ChannelLimits.check(PayMethods.BAITIAO, 100L, 4));
    }

    @Test
    void channelCode_七种支付方式映射正确() {
        assertEquals("MOCK_WECHAT", ChannelLimits.channelCode(PayMethods.WECHAT));
        assertEquals("MOCK_ALIPAY", ChannelLimits.channelCode(PayMethods.ALIPAY));
        assertEquals("BALANCE", ChannelLimits.channelCode(PayMethods.BALANCE));
        assertEquals("MOCK_BANK", ChannelLimits.channelCode(PayMethods.BANK_CARD));
        assertEquals("MOCK_UQR", ChannelLimits.channelCode(PayMethods.UNIONPAY));
        assertEquals("MOCK_HUABEI", ChannelLimits.channelCode(PayMethods.HUABEI));
        assertEquals("MOCK_BAITIAO", ChannelLimits.channelCode(PayMethods.BAITIAO));
    }
}
