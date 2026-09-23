package com.shop.product.price.service;

import com.shop.common.exception.BizException;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.mapper.ProductSkuMapper;
import com.shop.product.price.dto.PriceSnapshotDTO;
import com.shop.product.price.service.impl.PriceServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 五价取低：会员等级门槛、等低价先到优先级、快照装配与可售标记。
 */
@ExtendWith(MockitoExtension.class)
class PriceServiceImplTest {

    @Mock
    private ProductSkuMapper skuMapper;

    @InjectMocks
    private PriceServiceImpl priceService;

    private static final long SALE = 10000L;

    @Test
    void 无会员无活动_取销售价() {
        long best = priceService.selectBestPrice(SALE, null, null, null, 0);
        assertEquals(SALE, best);
        int type = priceService.selectBestPriceType(SALE, null, null, null, 0);
        assertEquals(PriceSnapshotDTO.TYPE_SALE, type);
    }

    @Test
    void L0会员_会员促销秒杀价都在_会员不可用但促销秒杀取低() {
        long best = priceService.selectBestPrice(SALE, 9000L, 8000L, 7000L, 0);
        assertEquals(7000L, best);
        assertEquals(PriceSnapshotDTO.TYPE_SECKILL,
                priceService.selectBestPriceType(SALE, 9000L, 8000L, 7000L, 0));
    }

    @Test
    void 会员等级为null_不能享受会员价() {
        assertEquals(SALE, priceService.selectBestPrice(SALE, 9000L, null, null, null));
        assertEquals(PriceSnapshotDTO.TYPE_SALE,
                priceService.selectBestPriceType(SALE, 9000L, null, null, null));
    }

    @Test
    void L1会员_会员价最低_取会员价() {
        long best = priceService.selectBestPrice(SALE, 9000L, null, null, 1);
        assertEquals(9000L, best);
        assertEquals(PriceSnapshotDTO.TYPE_MEMBER,
                priceService.selectBestPriceType(SALE, 9000L, null, null, 1));
    }

    @Test
    void L0会员_会员价更低也不可享受() {
        long best = priceService.selectBestPrice(SALE, 9000L, null, null, 0);
        assertEquals(SALE, best);
    }

    @Test
    void 促销价最低_取促销价() {
        assertEquals(8000L, priceService.selectBestPrice(SALE, 9000L, 8000L, null, 0));
        assertEquals(PriceSnapshotDTO.TYPE_PROMOTION,
                priceService.selectBestPriceType(SALE, 9000L, 8000L, null, 0));
    }

    @Test
    void 秒杀价最低_取秒杀价() {
        assertEquals(7000L, priceService.selectBestPrice(SALE, 9000L, 8000L, 7000L, 0));
        assertEquals(PriceSnapshotDTO.TYPE_SECKILL,
                priceService.selectBestPriceType(SALE, 9000L, 8000L, 7000L, 0));
    }

    @Test
    void 会员价全场最低_L2会员取会员价() {
        long best = priceService.selectBestPrice(SALE, 6500L, 8000L, 7000L, 2);
        assertEquals(6500L, best);
        assertEquals(PriceSnapshotDTO.TYPE_MEMBER,
                priceService.selectBestPriceType(SALE, 6500L, 8000L, 7000L, 2));
    }

    @Test
    void 促销秒杀未配置_按销售价() {
        assertEquals(SALE, priceService.selectBestPrice(SALE, 9000L, null, null, 0));
    }

    @Test
    void 等低价_保持先到优先级销售价() {
        // 促销价与销售价相等：严格小于才替换，类型保持销售价
        assertEquals(SALE, priceService.selectBestPrice(SALE, null, SALE, null, 0));
        assertEquals(PriceSnapshotDTO.TYPE_SALE,
                priceService.selectBestPriceType(SALE, null, SALE, null, 0));
    }

    @Test
    void 等低价_会员与促销同低_L1保持会员价() {
        assertEquals(9000L, priceService.selectBestPrice(SALE, 9000L, 9000L, null, 1));
        assertEquals(PriceSnapshotDTO.TYPE_MEMBER,
                priceService.selectBestPriceType(SALE, 9000L, 9000L, null, 1));
    }

    @Test
    void 销售价为空_抛参数异常() {
        assertThrows(BizException.class,
                () -> priceService.selectBestPrice(null, 9000L, 8000L, 7000L, 1));
    }

    @Test
    void 销售价为负_抛参数异常() {
        assertThrows(BizException.class,
                () -> priceService.selectBestPrice(-1L, 9000L, 8000L, 7000L, 1));
    }

    @Test
    void 快照_上架有库存_聚合五价且可售() {
        ProductSku sku = new ProductSku();
        sku.setId(100L);
        sku.setSpuId(200L);
        sku.setMerchantId(7L);
        sku.setMarketPriceFen(12000L);
        sku.setSalePriceFen(SALE);
        sku.setMemberPriceFen(9000L);
        sku.setPromotionPriceFen(8000L);
        sku.setSeckillPriceFen(7000L);
        sku.setAvailableStock(50L);
        sku.setLockedStock(3L);
        sku.setOccupiedStock(2L);
        sku.setWarnThreshold(10L);
        sku.setStatus(3);
        when(skuMapper.selectById(100L)).thenReturn(sku);

        PriceSnapshotDTO dto = priceService.snapshot(100L, 0);

        assertEquals(100L, dto.getSkuId());
        assertEquals(12000L, dto.getMarketPriceFen());
        assertEquals(SALE, dto.getSalePriceFen());
        assertEquals(9000L, dto.getMemberPriceFen());
        assertEquals(8000L, dto.getPromotionPriceFen());
        assertEquals(7000L, dto.getSeckillPriceFen());
        // L0 不能享受会员价，但秒杀价仍然参与取低
        assertEquals(7000L, dto.getFinalPriceFen());
        assertEquals(PriceSnapshotDTO.TYPE_SECKILL, dto.getFinalPriceType());
        assertEquals(50L, dto.getAvailableStock());
        assertTrue(dto.getSaleable());
    }

    @Test
    void 快照_非上架状态_不可售但仍出价() {
        ProductSku sku = new ProductSku();
        sku.setId(101L);
        sku.setSalePriceFen(SALE);
        sku.setAvailableStock(50L);
        sku.setStatus(4);
        when(skuMapper.selectById(101L)).thenReturn(sku);

        PriceSnapshotDTO dto = priceService.snapshot(101L, 1);
        assertFalse(dto.getSaleable());
        assertEquals(SALE, dto.getFinalPriceFen());
        // 未配置的价格原样透出 null，供调用方判断
        assertNull(dto.getMemberPriceFen());
        assertNull(dto.getPromotionPriceFen());
        assertNull(dto.getSeckillPriceFen());
    }

    @Test
    void 快照_上架但零库存_不可售() {
        ProductSku sku = new ProductSku();
        sku.setId(102L);
        sku.setSalePriceFen(SALE);
        sku.setAvailableStock(0L);
        sku.setStatus(3);
        when(skuMapper.selectById(102L)).thenReturn(sku);

        assertFalse(priceService.snapshot(102L, 0).getSaleable());
    }

    @Test
    void 快照_SKU不存在_抛NOT_FOUND() {
        when(skuMapper.selectById(404L)).thenReturn(null);
        assertThrows(BizException.class, () -> priceService.snapshot(404L, 0));
    }
}
