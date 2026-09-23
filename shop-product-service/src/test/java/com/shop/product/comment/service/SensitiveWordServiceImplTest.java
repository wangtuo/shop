package com.shop.product.comment.service;

import com.shop.common.exception.BizException;
import com.shop.product.comment.service.impl.SensitiveWordServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词组件：硬违禁拒绝、广告极限词等长替换、干净文本原样返回。
 */
class SensitiveWordServiceImplTest {

    private final SensitiveWordService service = new SensitiveWordServiceImpl();

    @Test
    void 干净文本_原样返回() {
        String text = "商品质量不错，物流也很快，五星好评。";
        assertEquals(text, service.checkAndFilter(text));
        assertFalse(service.containsSensitive(text));
    }

    @Test
    void 空文本_原样返回且不算敏感() {
        assertEquals("", service.checkAndFilter(""));
        assertNull(service.checkAndFilter(null));
        assertFalse(service.containsSensitive(""));
        assertFalse(service.containsSensitive(null));
    }

    @Test
    void 硬违禁词_直接拒绝() {
        assertThrows(BizException.class, () -> service.checkAndFilter("这里有人卖毒品"));
        assertThrows(BizException.class, () -> service.checkAndFilter("在线赌博网站"));
        assertThrows(BizException.class, () -> service.checkAndFilter("诈骗团伙落网"));
        assertTrue(service.containsSensitive("私下交易枪支"));
    }

    @Test
    void 广告极限词_等长星号替换() {
        String filtered = service.checkAndFilter("这是全网最便宜的商品，最低价了");
        assertEquals("这是全网***的商品，***了", filtered);
    }

    @Test
    void 多个不同长度极限词_分别等长替换() {
        // 最佳 2 字、第一品牌 4 字、绝无仅有 4 字
        String filtered = service.checkAndFilter("最佳选择，第一品牌，绝无仅有");
        assertEquals("**选择，****，****", filtered);
    }

    @Test
    void 替换后文本_仍标记为含敏感词() {
        assertTrue(service.containsSensitive("国家级认证产品"));
        assertTrue(service.containsSensitive("万能神器"));
    }

    @Test
    void 硬词与软词同时出现_优先拒绝() {
        assertThrows(BizException.class, () -> service.checkAndFilter("最便宜的色情服务"));
    }
}
