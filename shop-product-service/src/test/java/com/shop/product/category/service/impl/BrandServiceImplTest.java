package com.shop.product.category.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.exception.BizException;
import com.shop.common.result.PageResult;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.product.category.dto.BrandSaveRequest;
import com.shop.product.category.entity.ProductBrand;
import com.shop.product.category.mapper.ProductBrandMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 品牌：平台运营写鉴权、默认值、不存在校验、匿名分页。
 */
@ExtendWith(MockitoExtension.class)
class BrandServiceImplTest {

    @Mock
    private ProductBrandMapper brandMapper;

    @InjectMocks
    private BrandServiceImpl brandService;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProductBrand.class);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginPlatform() {
        UserContext.set(LoginUser.builder().userId(1L).userType(2).build());
    }

    private void loginMerchant() {
        UserContext.set(LoginUser.builder().userId(99L).userType(1).merchantId(7L).build());
    }

    private BrandSaveRequest request() {
        BrandSaveRequest req = new BrandSaveRequest();
        req.setName("华为");
        req.setInitial("H");
        req.setLogo("https://cdn/logo.png");
        return req;
    }

    private ProductBrand brand(long id) {
        ProductBrand brand = new ProductBrand();
        brand.setId(id);
        brand.setName("华为");
        brand.setInitial("H");
        brand.setSort(0);
        brand.setStatus(1);
        return brand;
    }

    @Test
    void 商户建品牌_禁止() {
        loginMerchant();
        assertThrows(BizException.class, () -> brandService.create(request()));
        verify(brandMapper, never()).insert(any());
    }

    @Test
    void 未登录建品牌_拒绝() {
        assertThrows(BizException.class, () -> brandService.create(request()));
    }

    @Test
    void 平台建品牌_默认启用排序0() {
        loginPlatform();

        brandService.create(request());

        ArgumentCaptor<ProductBrand> captor = ArgumentCaptor.forClass(ProductBrand.class);
        verify(brandMapper).insert(captor.capture());
        assertEquals("华为", captor.getValue().getName());
        assertEquals("H", captor.getValue().getInitial());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getSort());
    }

    @Test
    void 平台改品牌_成功() {
        loginPlatform();
        when(brandMapper.selectById(1L)).thenReturn(brand(1L));

        brandService.update(1L, request());

        verify(brandMapper).updateById(any());
    }

    @Test
    void 改不存在的品牌_抛NOT_FOUND() {
        loginPlatform();
        when(brandMapper.selectById(404L)).thenReturn(null);

        assertThrows(BizException.class, () -> brandService.update(404L, request()));
    }

    @Test
    void 商户删品牌_禁止() {
        loginMerchant();
        assertThrows(BizException.class, () -> brandService.delete(1L));
        verify(brandMapper, never()).update(any(), any());
    }

    @Test
    void 平台删品牌_成功() {
        loginPlatform();
        when(brandMapper.selectById(1L)).thenReturn(brand(1L));

        brandService.delete(1L);

        verify(brandMapper).update(any(), any());
    }

    @Test
    void 品牌分页_匿名可查返回记录() {
        Page<ProductBrand> page = new Page<>(1, 20);
        page.setRecords(List.of(brand(1L)));
        page.setTotal(1);
        when(brandMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<ProductBrand> result = brandService.page(null, null, "华为", null);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
    }
}
