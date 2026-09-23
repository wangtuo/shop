package com.shop.settlement.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** M-1 对外脱敏格式。 */
class AccountMaskTest {

    @Test
    void 银行卡号仅留后四位() {
        assertEquals("**** **** **** 1234", AccountMask.maskAccount("6222 0202 0000 1234".replace(" ", "")));
    }

    @Test
    void 支付宝账号仅留后四位() {
        assertEquals("**** **** **** 8899", AccountMask.maskAccount("zhangsan199001018899"));
    }

    @Test
    void 短账号整体打码() {
        assertEquals("****", AccountMask.maskAccount("123"));
    }

    @Test
    void 空值透传() {
        assertEquals("", AccountMask.maskAccount(""));
        assertEquals(null, AccountMask.maskAccount(null));
    }

    @Test
    void 中文姓名保留姓氏() {
        assertEquals("张*", AccountMask.maskName("张三"));
        assertEquals("欧**", AccountMask.maskName("欧阳修"));
        assertEquals("*", AccountMask.maskName("李"));
    }
}
