package com.shop.order.cart.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.enums.GoodsStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.order.cart.dto.CartAddRequest;
import com.shop.order.cart.dto.CartItemVO;
import com.shop.order.cart.dto.CartSelectRequest;
import com.shop.order.cart.dto.CartShopGroupVO;
import com.shop.order.cart.dto.CartViewVO;
import com.shop.order.cart.entity.CartItem;
import com.shop.order.cart.entity.Favorite;
import com.shop.order.cart.mapper.CartItemMapper;
import com.shop.order.cart.mapper.FavoriteMapper;
import com.shop.order.support.PurchaseLimitChecker;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购物车服务单测（mock Mapper/Feign，无中间件）：
 * 加购不锁库存、99 条目上限、合并数量、失效标记、价格变动提示、勾选/清理/分组视图。
 */
@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    private CartItemMapper cartItemMapper;
    @Mock
    private FavoriteMapper favoriteMapper;
    @Mock
    private ProductClient productClient;
    @Mock
    private PurchaseLimitChecker purchaseLimitChecker;

    @InjectMocks
    private CartServiceImpl cartService;

    private static final Long USER_ID = 1001L;

    /**
     * 纯 Mockito 环境下 MyBatis-Plus 不会引导 TableInfo，而 LambdaUpdateWrapper#set
     * 需要解析实体列缓存；测试前手工初始化一次。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CartItem.class);
    }

    private SkuDTO sku(long skuId, long price, int status, long stock) {
        return SkuDTO.builder()
                .skuId(skuId).spuId(2001L).merchantId(3001L).shopId(4001L)
                .skuName("测试SKU" + skuId).specText("颜色:红").image("http://img/" + skuId)
                .salePriceFen(price).status(status).availableStock(stock)
                .build();
    }

    private CartAddRequest addRequest(long skuId, int qty) {
        CartAddRequest req = new CartAddRequest();
        req.setSkuId(skuId);
        req.setQty(qty);
        return req;
    }

    /** 模拟 MyBatis 插入后回填自增/雪花主键（add 返回行 id 需要）。 */
    private void stubInsertAssignsId() {
        org.mockito.Mockito.doAnswer(inv -> {
            CartItem c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(9001L);
            }
            return 1;
        }).when(cartItemMapper).insert(any(CartItem.class));
    }

    @Test
    void add_newSku_insertsSnapshotAndValid() {
        SkuDTO sku = sku(11L, 9900, GoodsStatuses.ON_SALE.getCode(), 10L);
        when(productClient.getSku(11L)).thenReturn(Result.success(sku));
        when(cartItemMapper.selectOne(any())).thenReturn(null);
        when(cartItemMapper.selectCount(any())).thenReturn(0L);
        when(purchaseLimitChecker.invalidReason(sku)).thenReturn(0);
        stubInsertAssignsId();

        long cartId = cartService.add(USER_ID, addRequest(11L, 2));

        assertThat(cartId).isEqualTo(9001L);

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemMapper).insert(captor.capture());
        CartItem saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getSkuId()).isEqualTo(11L);
        assertThat(saved.getShopId()).isEqualTo(4001L);
        assertThat(saved.getPriceFen()).isEqualTo(9900L);
        assertThat(saved.getQty()).isEqualTo(2);
        assertThat(saved.getSelected()).isEqualTo(1);
        assertThat(saved.getInvalid()).isZero();
        // 加购不锁库存
        verify(productClient, never()).lockStock(any());
    }

    @Test
    void add_existingSku_mergesQty() {
        SkuDTO sku = sku(11L, 9900, GoodsStatuses.ON_SALE.getCode(), 10L);
        when(productClient.getSku(11L)).thenReturn(Result.success(sku));
        CartItem exist = new CartItem();
        exist.setId(7L);
        exist.setUserId(USER_ID);
        exist.setSkuId(11L);
        exist.setQty(3);
        when(cartItemMapper.selectOne(any())).thenReturn(exist);

        cartService.add(USER_ID, addRequest(11L, 2));

        assertThat(exist.getQty()).isEqualTo(5);
        verify(cartItemMapper).updateById(exist);
        verify(cartItemMapper, never()).insert(any());
        verify(cartItemMapper, never()).selectCount(any());
    }

    @Test
    void add_mergeExceedingSingleQty99_throwsCartLimit() {
        SkuDTO sku = sku(11L, 9900, GoodsStatuses.ON_SALE.getCode(), 10L);
        when(productClient.getSku(11L)).thenReturn(Result.success(sku));
        CartItem exist = new CartItem();
        exist.setQty(90);
        when(cartItemMapper.selectOne(any())).thenReturn(exist);

        assertThatThrownBy(() -> cartService.add(USER_ID, addRequest(11L, 20)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.CART_LIMIT.getCode());
        verify(cartItemMapper, never()).updateById(any());
    }

    @Test
    void add_whenAlready99Rows_throwsCartLimit() {
        SkuDTO sku = sku(12L, 9900, GoodsStatuses.ON_SALE.getCode(), 10L);
        when(productClient.getSku(12L)).thenReturn(Result.success(sku));
        when(cartItemMapper.selectOne(any())).thenReturn(null);
        when(cartItemMapper.selectCount(any())).thenReturn(99L);

        assertThatThrownBy(() -> cartService.add(USER_ID, addRequest(12L, 1)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.CART_LIMIT.getCode());
        verify(cartItemMapper, never()).insert(any());
    }

    @Test
    void add_offShelfSku_persistsInvalidFlag() {
        SkuDTO sku = sku(13L, 5000, 4, 10L);
        when(productClient.getSku(13L)).thenReturn(Result.success(sku));
        when(cartItemMapper.selectOne(any())).thenReturn(null);
        when(cartItemMapper.selectCount(any())).thenReturn(0L);
        when(purchaseLimitChecker.invalidReason(sku)).thenReturn(1);
        stubInsertAssignsId();

        cartService.add(USER_ID, addRequest(13L, 1));

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getInvalid()).isEqualTo(1);
        assertThat(captor.getValue().getInvalidReason()).isEqualTo(1);
    }

    @Test
    void add_skuNotFound_throws() {
        when(productClient.getSku(99L)).thenReturn(Result.success(null));
        assertThatThrownBy(() -> cartService.add(USER_ID, addRequest(99L, 1)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void updateQuantity_ownerCheckAndPersist() {
        CartItem item = new CartItem();
        item.setId(7L);
        item.setUserId(USER_ID);
        item.setQty(1);
        when(cartItemMapper.selectById(7L)).thenReturn(item);

        cartService.updateQuantity(USER_ID, 7L, 5);

        assertThat(item.getQty()).isEqualTo(5);
        verify(cartItemMapper).updateById(item);
    }

    @Test
    void updateQuantity_notOwner_throwsNotFound() {
        CartItem item = new CartItem();
        item.setId(7L);
        item.setUserId(2002L);
        when(cartItemMapper.selectById(7L)).thenReturn(item);

        assertThatThrownBy(() -> cartService.updateQuantity(USER_ID, 7L, 5))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void delete_zeroRows_throwsNotFound() {
        when(cartItemMapper.update(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> cartService.delete(USER_ID, 404L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void add_delete_reAddSameSku_deleteAgain_bothSucceedAndDeletedWritesRowId() {
        // P0-1 回归：加购→删除→重新加购同 SKU→再删除，两次加购均成功；
        // 删除 SQL 为 SET deleted = id（历史删除行互不冲突），DB 层每 user+sku 仅一行 deleted=0。
        SkuDTO sku = sku(21L, 9900, GoodsStatuses.ON_SALE.getCode(), 10L);
        when(productClient.getSku(21L)).thenReturn(Result.success(sku));
        // MP 查询自动追加 deleted=0：已删行不可见，故两次加购都查不到存活行
        when(cartItemMapper.selectOne(any())).thenReturn(null);
        when(cartItemMapper.selectCount(any())).thenReturn(0L);
        when(purchaseLimitChecker.invalidReason(sku)).thenReturn(0);
        when(cartItemMapper.update(any(), any())).thenReturn(1);
        stubInsertAssignsId();

        cartService.add(USER_ID, addRequest(21L, 1));
        cartService.delete(USER_ID, 501L);
        cartService.add(USER_ID, addRequest(21L, 1));
        cartService.delete(USER_ID, 502L);

        verify(cartItemMapper, times(2)).insert(any(CartItem.class));
        ArgumentCaptor<LambdaUpdateWrapper<CartItem>> wrappers =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(cartItemMapper, times(2)).update(eq(null), wrappers.capture());
        assertThat(wrappers.getAllValues()).hasSize(2);
        wrappers.getAllValues().forEach(w ->
                assertThat(w.getSqlSet()).contains("deleted = id"));
    }

    @Test
    void moveToFavorite_insertsFavoriteAndKeepsCartRow() {
        CartItem item = new CartItem();
        item.setId(7L);
        item.setUserId(USER_ID);
        item.setSkuId(11L);
        item.setSpuId(2001L);
        item.setMerchantId(3001L);
        item.setShopId(4001L);
        item.setSkuName("测试SKU11");
        item.setPriceFen(9900L);
        when(cartItemMapper.selectById(7L)).thenReturn(item);
        when(favoriteMapper.selectCount(any())).thenReturn(0L);

        cartService.moveToFavorite(USER_ID, 7L);

        verify(favoriteMapper).insert(any(Favorite.class));
        // 契约：收藏不删除购物车行
        verify(cartItemMapper, never()).update(any(), any());
    }

    @Test
    void moveToFavorite_whenFavoriteExists_skipsInsert() {
        CartItem item = new CartItem();
        item.setId(7L);
        item.setUserId(USER_ID);
        item.setSkuId(11L);
        when(cartItemMapper.selectById(7L)).thenReturn(item);
        when(favoriteMapper.selectCount(any())).thenReturn(1L);

        cartService.moveToFavorite(USER_ID, 7L);

        verify(favoriteMapper, never()).insert(any());
        // 收藏幂等且不删除购物车行
        verify(cartItemMapper, never()).update(any(), any());
    }

    @Test
    void select_withIds_updatesByIds() {
        CartSelectRequest req = new CartSelectRequest();
        req.setIds(List.of(1L, 2L));
        req.setSelected(0);

        cartService.select(USER_ID, req);

        verify(cartItemMapper).update(eq(null), any());
    }

    @Test
    void selectAll_withoutShop_updatesAllUserRows() {
        cartService.selectAll(USER_ID, 1, null);
        verify(cartItemMapper).update(eq(null), any());
    }

    @Test
    void invert_togglesEachRow() {
        CartItem a = new CartItem();
        a.setSelected(1);
        CartItem b = new CartItem();
        b.setSelected(0);
        when(cartItemMapper.selectList(any())).thenReturn(List.of(a, b));

        cartService.invert(USER_ID);

        assertThat(a.getSelected()).isZero();
        assertThat(b.getSelected()).isEqualTo(1);
        verify(cartItemMapper, org.mockito.Mockito.times(2)).updateById(any());
    }

    @Test
    void clearInvalid_deletesInvalidRowsAndReturnsCount() {
        when(cartItemMapper.update(any(), any())).thenReturn(3);
        assertThat(cartService.clearInvalid(USER_ID)).isEqualTo(3);
    }

    @Test
    void view_emptyCart_returnsZeroView() {
        when(cartItemMapper.selectList(any())).thenReturn(List.of());

        CartViewVO view = cartService.view(USER_ID);

        assertThat(view.getShopGroups()).isEmpty();
        assertThat(view.getTotalCount()).isZero();
        assertThat(view.getAllSelected()).isTrue();
    }

    @Test
    void view_groupsByShop_flagsPriceChangeAndInvalid_refreshesInvalidInDb() {
        CartItem shop1 = cartRow(1L, 11L, 4001L, 3001L, 9900L, 1, 1, 0, 0);
        CartItem shop1b = cartRow(2L, 12L, 4001L, 3001L, 5000L, 2, 1, 0, 0);
        CartItem shop2 = cartRow(3L, 13L, 4002L, 3002L, 8000L, 1, 1, 0, 0);
        when(cartItemMapper.selectList(any())).thenReturn(List.of(shop1, shop1b, shop2));

        SkuDTO s11 = sku(11L, 8800, GoodsStatuses.ON_SALE.getCode(), 5L);   // 降价
        SkuDTO s12 = sku(12L, 5000, GoodsStatuses.ON_SALE.getCode(), 5L);
        SkuDTO s13 = sku(13L, 8000, GoodsStatuses.ON_SALE.getCode(), 0L);   // 售罄 → 失效
        when(productClient.listSkus(any())).thenReturn(Result.success(List.of(s11, s12, s13)));
        when(purchaseLimitChecker.invalidReason(any())).thenReturn(0, 0, 2);

        CartViewVO view = cartService.view(USER_ID);

        // 失效状态变化落库
        verify(cartItemMapper).updateInvalid(eq(3L), eq(USER_ID), eq(1), eq(2));

        assertThat(view.getShopGroups()).hasSize(2);
        CartShopGroupVO g1 = view.getShopGroups().get(0);
        assertThat(g1.getShopId()).isEqualTo(4001L);
        assertThat(g1.getSelectedCount()).isEqualTo(2);
        assertThat(g1.getSelectedQty()).isEqualTo(3);
        // 8800*1 + 5000*2
        assertThat(g1.getSelectedAmountFen()).isEqualTo(18800L);

        CartItemVO priceChanged = g1.getItems().stream()
                .filter(i -> i.getSkuId() == 11L).findFirst().orElseThrow();
        assertThat(priceChanged.getPriceChanged()).isTrue();
        assertThat(priceChanged.getCurrentPriceFen()).isEqualTo(8800L);
        assertThat(priceChanged.getAddPriceFen()).isEqualTo(9900L);

        // 失效商品不计入勾选金额/数量
        assertThat(view.getInvalidCount()).isEqualTo(1);
        assertThat(view.getTotalQty()).isEqualTo(4);
        assertThat(view.getSelectedAmountFen()).isEqualTo(18800L);
    }

    @Test
    void view_productDomainDown_fallsBackToSnapshotsWithoutMassInvalidFlag() {
        CartItem row = cartRow(1L, 11L, 4001L, 3001L, 9900L, 1, 1, 0, 0);
        when(cartItemMapper.selectList(any())).thenReturn(List.of(row));
        when(productClient.listSkus(any()))
                .thenReturn(Result.fail(ErrorCode.DEPENDENCY_FAIL, "商品域不可用"));
        // 拿不到 SKU 时 invalidReason 判定为已删除，但视图仍按快照价展示
        when(purchaseLimitChecker.invalidReason(null)).thenReturn(3);

        CartViewVO view = cartService.view(USER_ID);

        assertThat(view.getShopGroups()).hasSize(1);
        CartItemVO vo = view.getShopGroups().get(0).getItems().get(0);
        assertThat(vo.getCurrentPriceFen()).isEqualTo(0L);
        assertThat(vo.getInvalid()).isEqualTo(1);
    }

    private CartItem cartRow(long id, long skuId, long shopId, long merchantId,
                             long priceFen, int qty, int selected, int invalid, int reason) {
        CartItem item = new CartItem();
        item.setId(id);
        item.setUserId(USER_ID);
        item.setSkuId(skuId);
        item.setSpuId(2001L);
        item.setShopId(shopId);
        item.setMerchantId(merchantId);
        item.setSkuName("SKU" + skuId);
        item.setPriceFen(priceFen);
        item.setQty(qty);
        item.setSelected(selected);
        item.setInvalid(invalid);
        item.setInvalidReason(reason);
        return item;
    }
}
