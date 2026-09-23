package com.shop.product.category.service.impl;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.product.category.entity.ProductCategory;
import com.shop.product.category.entity.ProductSpuCategory;
import com.shop.product.category.mapper.ProductCategoryMapper;
import com.shop.product.category.mapper.ProductSpuCategoryMapper;
import com.shop.product.goods.entity.ProductSpu;
import com.shop.product.goods.mapper.ProductSpuMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13：SPU 虚拟类目多挂载（多挂载查询、主归属拒卸、整批同步）。
 */
@ExtendWith(MockitoExtension.class)
class SpuCategoryMountServiceImplTest {

    @Mock
    private ProductSpuMapper spuMapper;
    @Mock
    private ProductCategoryMapper categoryMapper;
    @Mock
    private ProductSpuCategoryMapper mountMapper;

    private SpuCategoryMountServiceImpl mountService;

    private static final long MERCHANT = 7L;
    private static final long SPU_ID = 200L;
    private static final long CATEGORY3 = 300L;
    private static final long VIRTUAL_CAT_1 = 201L;
    private static final long VIRTUAL_CAT_2 = 202L;

    @BeforeEach
    void setUp() {
        mountService = new SpuCategoryMountServiceImpl(spuMapper, categoryMapper, mountMapper);
        UserContext.set(LoginUser.builder().userId(99L).userType(1).merchantId(MERCHANT).build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ProductSpu ownedSpu() {
        ProductSpu spu = new ProductSpu();
        spu.setId(SPU_ID);
        spu.setMerchantId(MERCHANT);
        spu.setCategory3Id(CATEGORY3);
        spu.setStatus(3);
        return spu;
    }

    private ProductCategory category(long id, int level) {
        ProductCategory c = new ProductCategory();
        c.setId(id);
        c.setLevel(level);
        c.setStatus(1);
        return c;
    }

    private ProductSpuCategory mount(long categoryId, int level) {
        ProductSpuCategory m = new ProductSpuCategory();
        m.setSpuId(SPU_ID);
        m.setCategoryId(categoryId);
        m.setCategoryLevel(level);
        m.setMountType(2);
        return m;
    }

    @Test
    void 主归属加两个虚拟挂载_按任一挂载类目可查到SPU() {
        when(spuMapper.selectById(SPU_ID)).thenReturn(ownedSpu());
        when(categoryMapper.selectById(VIRTUAL_CAT_1)).thenReturn(category(VIRTUAL_CAT_1, 2));
        when(categoryMapper.selectById(VIRTUAL_CAT_2)).thenReturn(category(VIRTUAL_CAT_2, 1));

        mountService.mount(SPU_ID, VIRTUAL_CAT_1);
        mountService.mount(SPU_ID, VIRTUAL_CAT_2);

        ArgumentCaptor<ProductSpuCategory> captor = ArgumentCaptor.forClass(ProductSpuCategory.class);
        verify(mountMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<ProductSpuCategory> inserted = captor.getAllValues();
        assertEquals(2, inserted.size());
        assertEquals(2, inserted.get(0).getMountType());
        assertEquals(2, inserted.get(0).getCategoryLevel());
        assertEquals(1, inserted.get(1).getCategoryLevel());

        // 按任一挂载类目查询（mapper 关系）返回该 SPU；service 读路径返回两条挂载
        when(mountMapper.selectBySpu(SPU_ID))
                .thenReturn(List.of(mount(VIRTUAL_CAT_1, 2), mount(VIRTUAL_CAT_2, 1)));
        List<ProductSpuCategory> mounts = mountService.listMounts(SPU_ID);
        assertEquals(2, mounts.size());
    }

    @Test
    void 挂载主归属类目_拒绝() {
        when(spuMapper.selectById(SPU_ID)).thenReturn(ownedSpu());
        BizException ex = assertThrows(BizException.class,
                () -> mountService.mount(SPU_ID, CATEGORY3));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(mountMapper, never()).insert(any());
    }

    @Test
    void 卸载主归属类目_拒绝() {
        when(spuMapper.selectById(SPU_ID)).thenReturn(ownedSpu());
        BizException ex = assertThrows(BizException.class,
                () -> mountService.unmount(SPU_ID, CATEGORY3));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(mountMapper, never()).deleteOne(anyLong(), anyLong());
    }

    @Test
    void 卸载虚拟挂载_幂等执行() {
        when(spuMapper.selectById(SPU_ID)).thenReturn(ownedSpu());
        mountService.unmount(SPU_ID, VIRTUAL_CAT_1);
        verify(mountMapper).deleteOne(SPU_ID, VIRTUAL_CAT_1);
    }

    @Test
    void 非本店商户挂载_拒绝() {
        ProductSpu other = ownedSpu();
        other.setMerchantId(8L);
        when(spuMapper.selectById(SPU_ID)).thenReturn(other);
        assertThrows(BizException.class, () -> mountService.mount(SPU_ID, VIRTUAL_CAT_1));
        verify(mountMapper, never()).insert(any());
    }

    @Test
    void 保存时整批同步_替换虚拟挂载并剔除主归属_null不改动() {
        when(spuMapper.selectById(SPU_ID)).thenReturn(ownedSpu());
        when(categoryMapper.selectById(VIRTUAL_CAT_1)).thenReturn(category(VIRTUAL_CAT_1, 2));
        when(categoryMapper.selectById(VIRTUAL_CAT_2)).thenReturn(category(VIRTUAL_CAT_2, 2));

        // null：不动既有挂载
        mountService.syncVirtualMounts(SPU_ID, null);
        verify(mountMapper, never()).deleteVirtualBySpu(anyLong());

        // 非 null：全量替换，主归属 CATEGORY3 被剔除，仅插两个虚拟类目
        mountService.syncVirtualMounts(SPU_ID, List.of(VIRTUAL_CAT_1, CATEGORY3, VIRTUAL_CAT_2, VIRTUAL_CAT_1));
        verify(mountMapper).deleteVirtualBySpu(SPU_ID);
        ArgumentCaptor<ProductSpuCategory> captor = ArgumentCaptor.forClass(ProductSpuCategory.class);
        verify(mountMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertEquals(VIRTUAL_CAT_1, captor.getAllValues().get(0).getCategoryId());
        assertEquals(VIRTUAL_CAT_2, captor.getAllValues().get(1).getCategoryId());
    }
}
