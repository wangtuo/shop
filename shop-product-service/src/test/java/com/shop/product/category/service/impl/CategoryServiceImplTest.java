package com.shop.product.category.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.product.category.dto.CategorySaveRequest;
import com.shop.product.category.dto.CategoryTreeDTO;
import com.shop.product.category.entity.ProductCategory;
import com.shop.product.category.mapper.ProductCategoryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 类目：平台运营写鉴权、层级推导/三级上限、子类目保护删除、树装配。
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private ProductCategoryMapper categoryMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProductCategory.class);
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

    private CategorySaveRequest request(long pid, String name) {
        CategorySaveRequest req = new CategorySaveRequest();
        req.setPid(pid);
        req.setName(name);
        return req;
    }

    private ProductCategory category(long id, long pid, int level) {
        ProductCategory c = new ProductCategory();
        c.setId(id);
        c.setPid(pid);
        c.setLevel(level);
        c.setName("L" + level);
        c.setStatus(1);
        c.setSort(0);
        return c;
    }

    @Test
    void 未登录建类目_拒绝() {
        assertThrows(BizException.class, () -> categoryService.create(request(0L, "手机")));
        verify(categoryMapper, never()).insert(any());
    }

    @Test
    void 商户建类目_禁止() {
        loginMerchant();
        BizException ex = assertThrows(BizException.class,
                () -> categoryService.create(request(0L, "手机")));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void 平台建根类目_层级为1() {
        loginPlatform();

        categoryService.create(request(0L, "手机数码"));

        ArgumentCaptor<ProductCategory> captor = ArgumentCaptor.forClass(ProductCategory.class);
        verify(categoryMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getLevel());
        assertEquals(0L, captor.getValue().getPid());
        assertEquals(1, captor.getValue().getStatus());
    }

    @Test
    void 平台建二级类目_层级随父节点() {
        loginPlatform();
        when(categoryMapper.selectById(10L)).thenReturn(category(10L, 0L, 1));

        categoryService.create(request(10L, "手机"));

        ArgumentCaptor<ProductCategory> captor = ArgumentCaptor.forClass(ProductCategory.class);
        verify(categoryMapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getLevel());
    }

    @Test
    void 三级下再建子类目_拒绝() {
        loginPlatform();
        when(categoryMapper.selectById(300L)).thenReturn(category(300L, 200L, 3));

        BizException ex = assertThrows(BizException.class,
                () -> categoryService.create(request(300L, "四级")));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(categoryMapper, never()).insert(any());
    }

    @Test
    void 父类目不存在_拒绝() {
        loginPlatform();
        when(categoryMapper.selectById(999L)).thenReturn(null);
        assertThrows(BizException.class, () -> categoryService.create(request(999L, "子类目")));
    }

    @Test
    void 商户改类目_禁止() {
        loginMerchant();
        assertThrows(BizException.class,
                () -> categoryService.update(10L, request(0L, "改名")));
        verify(categoryMapper, never()).updateById(any());
    }

    @Test
    void 平台改名_成功() {
        loginPlatform();
        when(categoryMapper.selectById(10L)).thenReturn(category(10L, 0L, 1));

        categoryService.update(10L, request(0L, "手机数码"));

        verify(categoryMapper).updateById(any());
    }

    @Test
    void 删除类目_存在子类目_拒绝() {
        loginPlatform();
        when(categoryMapper.selectById(10L)).thenReturn(category(10L, 0L, 1));
        when(categoryMapper.selectCount(any())).thenReturn(2L);

        BizException ex = assertThrows(BizException.class, () -> categoryService.delete(10L));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(categoryMapper, never()).deleteById(any());
    }

    @Test
    void 删除叶子类目_成功() {
        loginPlatform();
        when(categoryMapper.selectById(300L)).thenReturn(category(300L, 200L, 3));
        when(categoryMapper.selectCount(any())).thenReturn(0L);

        categoryService.delete(300L);

        verify(categoryMapper).deleteById(300L);
    }

    @Test
    void 类目树_父子归并_仅返回根节点() {
        ProductCategory root = category(1L, 0L, 1);
        root.setName("手机数码");
        ProductCategory child = category(2L, 1L, 2);
        child.setName("手机");
        ProductCategory orphan = category(3L, 999L, 2);
        when(categoryMapper.selectList(any())).thenReturn(List.of(root, child, orphan));

        List<CategoryTreeDTO> tree = categoryService.tree();

        // 根 + 找不到父节点的孤儿节点都在顶层
        assertEquals(2, tree.size());
        CategoryTreeDTO rootNode = tree.stream().filter(n -> n.getId() == 1L).findFirst().orElseThrow();
        assertEquals(1, rootNode.getChildren().size());
        assertEquals(2L, rootNode.getChildren().get(0).getId());
        assertTrue(tree.stream().anyMatch(n -> n.getId() == 3L));
    }
}
