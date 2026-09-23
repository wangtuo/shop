package com.shop.product.goods.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.api.product.dto.SpuDTO;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.product.category.entity.ProductCategory;
import com.shop.product.category.mapper.ProductCategoryMapper;
import com.shop.product.category.service.AttrKeyService;
import com.shop.product.category.service.SpuCategoryMountService;
import com.shop.product.goods.dto.SkuSaveRequest;
import com.shop.product.goods.dto.SpuAuditRequest;
import com.shop.product.goods.dto.SpuBrowseQuery;
import com.shop.product.goods.dto.SpuDetailVO;
import com.shop.product.goods.dto.SpuManageQuery;
import com.shop.product.goods.dto.SpuSaveRequest;
import com.shop.product.goods.dto.SpuSaveResult;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.entity.ProductSpu;
import com.shop.product.goods.mapper.ProductSkuMapper;
import com.shop.product.goods.mapper.ProductSpuMapper;
import com.shop.product.goods.statemachine.GoodsStateMachine;
import com.shop.product.goods.support.SpuDetailCache;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPU 商户/平台鉴权、编辑约束、状态机联动条件更新与详情缓存路径。
 */
@ExtendWith(MockitoExtension.class)
class SpuServiceImplTest {

    @Mock
    private ProductSpuMapper spuMapper;
    @Mock
    private ProductSkuMapper skuMapper;
    @Mock
    private ProductCategoryMapper categoryMapper;
    @Mock
    private SpuDetailCache spuDetailCache;
    @Mock
    private SpuCategoryMountService spuCategoryMountService;
    @Mock
    private AttrKeyService attrKeyService;

    private SpuServiceImpl spuService;

    private static final long MERCHANT = 7L;
    private static final long OTHER_MERCHANT = 8L;
    private static final long SPU_ID = 200L;
    private static final long CATEGORY3 = 300L;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProductSpu.class);
        TableInfoHelper.initTableInfo(assistant, ProductSku.class);
    }

    @BeforeEach
    void setUp() {
        spuService = new SpuServiceImpl(spuMapper, skuMapper, categoryMapper,
                new GoodsStateMachine(), spuDetailCache, spuCategoryMountService, attrKeyService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginMerchant(long merchantId) {
        UserContext.set(LoginUser.builder().userId(99L).userType(1).merchantId(merchantId).build());
    }

    private void loginPlatform() {
        UserContext.set(LoginUser.builder().userId(1L).userType(2).build());
    }

    private ProductCategory enabledCategory3() {
        ProductCategory category = new ProductCategory();
        category.setId(CATEGORY3);
        category.setLevel(3);
        category.setStatus(1);
        return category;
    }

    private SpuSaveRequest saveRequest() {
        SpuSaveRequest req = new SpuSaveRequest();
        req.setShopId(11L);
        req.setName("测试商品");
        req.setBrandId(5L);
        req.setCategory3Id(CATEGORY3);
        req.setMainImage("https://cdn/main.jpg");
        req.setDetailJson("<p>detail</p>");
        SkuSaveRequest sku = new SkuSaveRequest();
        sku.setSkuCode("SKU001");
        sku.setSpecText("红色/L");
        sku.setSalePriceFen(10000L);
        sku.setStockQty(100L);
        req.setSkus(List.of(sku));
        return req;
    }

    private ProductSpu spu(long merchantId, int status) {
        ProductSpu spu = new ProductSpu();
        spu.setId(SPU_ID);
        spu.setMerchantId(merchantId);
        spu.setShopId(11L);
        spu.setCategory3Id(CATEGORY3);
        spu.setName("测试商品");
        spu.setStatus(status);
        spu.setVersion(0);
        return spu;
    }

    // ---------------- 新建 ----------------

    @Test
    void 商户建商品_挂三级类目_落草稿并建SKU() {
        loginMerchant(MERCHANT);
        when(categoryMapper.selectById(CATEGORY3)).thenReturn(enabledCategory3());

        spuService.create(saveRequest());

        ArgumentCaptor<ProductSpu> spuCaptor = ArgumentCaptor.forClass(ProductSpu.class);
        verify(spuMapper).insert(spuCaptor.capture());
        assertEquals(0, spuCaptor.getValue().getStatus());
        assertEquals(MERCHANT, spuCaptor.getValue().getMerchantId());
        ArgumentCaptor<ProductSku> skuCaptor = ArgumentCaptor.forClass(ProductSku.class);
        verify(skuMapper).insert(skuCaptor.capture());
        assertEquals(100L, skuCaptor.getValue().getAvailableStock());
        assertEquals(0, skuCaptor.getValue().getStatus());
        assertEquals(10000L, skuCaptor.getValue().getSalePriceFen());
    }

    @Test
    void 未登录商户_禁止建商品() {
        assertThrows(BizException.class, () -> spuService.create(saveRequest()));
        verify(spuMapper, never()).insert(any());
    }

    @Test
    void 类目停用或非三级_禁止建商品() {
        loginMerchant(MERCHANT);
        ProductCategory category = enabledCategory3();
        category.setStatus(0);
        when(categoryMapper.selectById(CATEGORY3)).thenReturn(category);

        assertThrows(BizException.class, () -> spuService.create(saveRequest()));

        category.setStatus(1);
        category.setLevel(2);
        assertThrows(BizException.class, () -> spuService.create(saveRequest()));
    }

    // ---------------- 提交审核 ----------------

    @Test
    void 店主提交草稿审核_条件更新为待审核() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 0));
        when(spuMapper.updateStatusIf(SPU_ID, 0, 1)).thenReturn(1);

        spuService.submitAudit(SPU_ID);

        verify(spuMapper).updateStatusIf(SPU_ID, 0, 1);
        verify(skuMapper).update(any(), any());
        verify(spuDetailCache).evict(SPU_ID);
    }

    @Test
    void 非店主提交审核_禁止() {
        loginMerchant(OTHER_MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 0));

        BizException ex = assertThrows(BizException.class, () -> spuService.submitAudit(SPU_ID));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(spuMapper, never()).updateStatusIf(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 在售商品提交审核_状态机拒绝() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 3));

        assertThrows(BizException.class, () -> spuService.submitAudit(SPU_ID));
        verify(spuMapper, never()).updateStatusIf(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 提交审核并发已被改状态_条件更新0行抛冲突() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 0));
        when(spuMapper.updateStatusIf(SPU_ID, 0, 1)).thenReturn(0);

        assertThrows(BizException.class, () -> spuService.submitAudit(SPU_ID));
        verify(spuDetailCache, never()).evict(anyLong());
    }

    // ---------------- 平台审核/违规 ----------------

    @Test
    void 平台审核通过_上架并记录审核信息() {
        loginPlatform();
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 1));
        when(spuMapper.updateStatusIf(SPU_ID, 1, 3)).thenReturn(1);

        SpuAuditRequest req = new SpuAuditRequest();
        req.setPass(true);
        req.setRemark("通过");
        spuService.audit(SPU_ID, req);

        verify(spuMapper).updateStatusIf(SPU_ID, 1, 3);
        verify(spuMapper).update(eq(null), any());
    }

    @Test
    void 商户不能审核_禁止() {
        loginMerchant(MERCHANT);
        SpuAuditRequest req = new SpuAuditRequest();
        req.setPass(true);

        assertThrows(BizException.class, () -> spuService.audit(SPU_ID, req));
    }

    @Test
    void 平台审核驳回_进入审核拒绝() {
        loginPlatform();
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 1));
        when(spuMapper.updateStatusIf(SPU_ID, 1, 2)).thenReturn(1);

        SpuAuditRequest req = new SpuAuditRequest();
        req.setPass(false);
        req.setRemark("主图不合规");
        spuService.audit(SPU_ID, req);

        verify(spuMapper).updateStatusIf(SPU_ID, 1, 2);
    }

    @Test
    void 平台违规下架_在售商品进入违规态() {
        loginPlatform();
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 3));
        when(spuMapper.updateStatusIf(SPU_ID, 3, 6)).thenReturn(1);

        spuService.violationOff(SPU_ID, "虚假宣传");

        verify(spuMapper).updateStatusIf(SPU_ID, 3, 6);
        verify(spuDetailCache).evict(SPU_ID);
    }

    // ---------------- 上下架/删除 ----------------

    @Test
    void 店主下架_在售转下架成功() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 3));
        when(spuMapper.updateStatusIf(SPU_ID, 3, 4)).thenReturn(1);

        spuService.offSale(SPU_ID);

        verify(spuMapper).updateStatusIf(SPU_ID, 3, 4);
    }

    @Test
    void 店主上架_下架转在售成功() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 4));
        when(spuMapper.updateStatusIf(SPU_ID, 4, 3)).thenReturn(1);

        spuService.onSale(SPU_ID);

        verify(spuMapper).updateStatusIf(SPU_ID, 4, 3);
    }

    @Test
    void 删除商品_SKU存在锁定库存_拒绝() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 0));
        ProductSku locked = new ProductSku();
        locked.setId(1L);
        locked.setSpuId(SPU_ID);
        locked.setSkuCode("SKU001");
        locked.setLockedStock(5L);
        locked.setOccupiedStock(0L);
        when(skuMapper.selectList(any())).thenReturn(List.of(locked));

        BizException ex = assertThrows(BizException.class, () -> spuService.delete(SPU_ID));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(spuMapper, never()).deleteById(anyLong());
    }

    @Test
    void 店主删除草稿商品_无锁定库存_逻辑删除() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 0));
        when(skuMapper.selectList(any())).thenReturn(List.of());
        when(spuMapper.updateStatusIf(SPU_ID, 0, 7)).thenReturn(1);

        spuService.delete(SPU_ID);

        verify(spuMapper).updateStatusIf(SPU_ID, 0, 7);
        verify(spuMapper).deleteById(SPU_ID);
        // 状态流转 transit 也可能 update SKU；精确断言存在一次 SET deleted = id 的逻辑删除
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ProductSku>> skuUpdates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(skuMapper, atLeastOnce()).update(any(), skuUpdates.capture());
        assertTrue(skuUpdates.getAllValues().stream()
                .anyMatch(w -> w.getSqlSet().contains("deleted = id")));
    }

    // ---------------- 浏览详情 ----------------

    @Test
    void 浏览详情_草稿不可见_抛不可售() {
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 0));

        BizException ex = assertThrows(BizException.class, () -> spuService.browseDetail(SPU_ID));
        assertEquals(ErrorCode.GOODS_NOT_SALE.getCode(), ex.getCode());
    }

    @Test
    void 浏览详情_不存在_抛NOT_FOUND() {
        when(spuMapper.selectById(SPU_ID)).thenReturn(null);
        assertThrows(BizException.class, () -> spuService.browseDetail(SPU_ID));
    }

    @Test
    void 浏览详情_在售缓存未命中_装配并回填缓存() {
        ProductSpu onSale = spu(MERCHANT, 3);
        when(spuMapper.selectById(SPU_ID)).thenReturn(onSale);
        when(spuDetailCache.get(SPU_ID, 0L)).thenReturn(null);
        when(skuMapper.selectList(any())).thenReturn(List.of());

        SpuDetailVO vo = spuService.browseDetail(SPU_ID);

        assertNotNull(vo);
        assertNotNull(vo.getSkus());
        verify(spuDetailCache).put(eq(SPU_ID), eq(0L), any(SpuDetailVO.class));
    }

    @Test
    void 浏览详情_缓存命中_不查SKU直接返回() {
        ProductSpu onSale = spu(MERCHANT, 3);
        SpuDetailVO cached = new SpuDetailVO();
        when(spuMapper.selectById(SPU_ID)).thenReturn(onSale);
        when(spuDetailCache.get(SPU_ID, 0L)).thenReturn(cached);

        assertEquals(cached, spuService.browseDetail(SPU_ID));
        verify(skuMapper, never()).selectList(any());
        verify(spuDetailCache, never()).put(anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    // ---------------- 商户管理详情鉴权 ----------------

    @Test
    void 管理详情_店主可见() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 3));
        when(skuMapper.selectList(any())).thenReturn(List.of());

        assertNotNull(spuService.manageDetail(SPU_ID));
    }

    @Test
    void 管理详情_他店商户不可见() {
        loginMerchant(OTHER_MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 3));

        assertThrows(BizException.class, () -> spuService.manageDetail(SPU_ID));
    }

    @Test
    void 管理详情_平台可见() {
        loginPlatform();
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 3));
        when(skuMapper.selectList(any())).thenReturn(List.of());

        assertNotNull(spuService.manageDetail(SPU_ID));
    }

    // ---------------- 编辑 ----------------

    private ProductSku persistedSku(long id, long locked, long occupied) {
        ProductSku sku = new ProductSku();
        sku.setId(id);
        sku.setSpuId(SPU_ID);
        sku.setMerchantId(MERCHANT);
        sku.setSkuCode("SKU00" + id);
        sku.setLockedStock(locked);
        sku.setOccupiedStock(occupied);
        return sku;
    }

    @Test
    void 店主编辑草稿商品_保留一个SKU删除一个SKU_删缓存() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 0));
        when(categoryMapper.selectById(CATEGORY3)).thenReturn(enabledCategory3());
        when(skuMapper.selectList(any())).thenReturn(List.of(persistedSku(1L, 0, 0), persistedSku(2L, 0, 0)));

        SpuSaveRequest req = saveRequest();
        req.getSkus().get(0).setSkuId(1L);

        spuService.update(SPU_ID, req);

        verify(spuMapper).updateById(any());
        verify(skuMapper).updateById(any());
        verify(skuMapper).update(any(), any());
        verify(spuDetailCache).evict(SPU_ID);
    }

    @Test
    void 编辑在售商品_拒绝() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 3));

        BizException ex = assertThrows(BizException.class, () -> spuService.update(SPU_ID, saveRequest()));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(spuMapper, never()).updateById(any());
    }

    @Test
    void 非店主编辑_拒绝() {
        loginMerchant(OTHER_MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 2));

        assertThrows(BizException.class, () -> spuService.update(SPU_ID, saveRequest()));
    }

    @Test
    void 编辑删除SKU时存在占用库存_拒绝() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(MERCHANT, 0));
        when(categoryMapper.selectById(CATEGORY3)).thenReturn(enabledCategory3());
        when(skuMapper.selectList(any())).thenReturn(List.of(persistedSku(1L, 0, 0), persistedSku(2L, 0, 3)));

        SpuSaveRequest req = saveRequest();
        req.getSkus().get(0).setSkuId(1L);

        assertThrows(BizException.class, () -> spuService.update(SPU_ID, req));
        verify(skuMapper, never()).update(any(), any());
    }

    @Test
    void 新建商品_SKU编码唯一键冲突_抛冲突() {
        loginMerchant(MERCHANT);
        when(categoryMapper.selectById(CATEGORY3)).thenReturn(enabledCategory3());
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_sku_code"))
                .when(skuMapper).insert(any(ProductSku.class));

        assertThrows(BizException.class, () -> spuService.create(saveRequest()));
    }

    // ---------------- 分页 ----------------

    @Test
    void 商户管理分页_强制本店维度() {
        loginMerchant(MERCHANT);
        when(spuMapper.selectPage(any(), any())).thenReturn(new Page<ProductSpu>());

        PageResult<SpuDTO> result = spuService.managePage(new SpuManageQuery());

        assertEquals(0, result.getTotal());
    }

    @Test
    void 平台后台分页_允许() {
        loginPlatform();
        when(spuMapper.selectPage(any(), any())).thenReturn(new Page<ProductSpu>());

        assertEquals(0, spuService.adminPage(new SpuManageQuery()).getTotal());
    }

    @Test
    void 商户不能用平台分页_禁止() {
        loginMerchant(MERCHANT);
        assertThrows(BizException.class, () -> spuService.adminPage(new SpuManageQuery()));
    }

    @Test
    void C端浏览分页_只返回上架商品() {
        when(spuMapper.selectPage(any(), any())).thenReturn(new Page<ProductSpu>());

        PageResult<SpuDTO> result = spuService.browsePage(new SpuBrowseQuery());

        assertEquals(0, result.getTotal());
    }

    @Test
    void 建品保存_返回属性主数据告警并同步虚拟挂载() {
        loginMerchant(MERCHANT);
        when(categoryMapper.selectById(CATEGORY3)).thenReturn(enabledCategory3());
        when(attrKeyService.validateAttrs(eq(CATEGORY3), any()))
                .thenReturn(List.of("属性[材质]未收录类目属性主数据，仅按快照保存"));
        // 模拟 MyBatis-Plus ASSIGN_ID：insert 回填主键
        org.mockito.Mockito.doAnswer(inv -> {
            ((ProductSpu) inv.getArgument(0)).setId(SPU_ID);
            return 1;
        }).when(spuMapper).insert(any(ProductSpu.class));
        SpuSaveRequest req = saveRequest();
        req.setCategoryIds(List.of(888L, 999L));

        SpuSaveResult result = spuService.create(req);

        assertEquals(SPU_ID, result.getSpuId());
        assertEquals(1, result.getWarnings().size());
        verify(spuCategoryMountService).syncVirtualMounts(SPU_ID, List.of(888L, 999L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void C端按虚拟挂载类目查询_条件并入spu_category关系表() {
        when(spuMapper.selectPage(any(), any())).thenReturn(new Page<ProductSpu>());
        SpuBrowseQuery query = new SpuBrowseQuery();
        query.setCategory3Id(999L);

        spuService.browsePage(query);

        ArgumentCaptor<LambdaQueryWrapper<ProductSpu>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.contains("t_product_spu_category"), "虚拟挂载子查询应并入 SQL: " + sql);
        assertTrue(sql.contains("category3_id"), "主归属条件应保留: " + sql);
    }
}
