package com.shop.order.cart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.SkuDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.order.cart.dto.CartAddRequest;
import com.shop.order.cart.dto.CartItemVO;
import com.shop.order.cart.dto.CartSelectRequest;
import com.shop.order.cart.dto.CartShopGroupVO;
import com.shop.order.cart.dto.CartViewVO;
import com.shop.order.cart.entity.CartItem;
import com.shop.order.cart.entity.Favorite;
import com.shop.order.cart.mapper.CartItemMapper;
import com.shop.order.cart.mapper.FavoriteMapper;
import com.shop.order.cart.service.CartService;
import com.shop.order.support.FeignResults;
import com.shop.order.support.PurchaseLimitChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 购物车服务实现（design 5.4）。
 * <p>每用户购物车上限 99 个商品条目（SKU 行数）；加购不扣库存；
 * 失效（下架/售罄/删除）实时由商品域判定并持久化，价格变动仅提示不删除。
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    /** 每用户购物车商品条目上限（design：99 件商品 → 99 个 SKU 条目） */
    public static final int MAX_CART_ITEMS = 99;
    /** 单个 SKU 最大购买数量 */
    public static final int MAX_SINGLE_QTY = 99;

    private final CartItemMapper cartItemMapper;
    private final FavoriteMapper favoriteMapper;
    private final ProductClient productClient;
    private final PurchaseLimitChecker purchaseLimitChecker;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long add(Long userId, CartAddRequest request) {
        SkuDTO sku = FeignResults.unwrap(productClient.getSku(request.getSkuId()));
        if (sku == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        CartItem exist = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getSkuId, request.getSkuId()));
        if (exist != null) {
            int merged = exist.getQty() + request.getQty();
            if (merged > MAX_SINGLE_QTY) {
                throw new BizException(ErrorCode.CART_LIMIT, "单个商品数量不能超过 " + MAX_SINGLE_QTY + " 件");
            }
            exist.setQty(merged);
            cartItemMapper.updateById(exist);
            return exist.getId();
        }
        Long count = cartItemMapper.selectCount(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId));
        if (count != null && count >= MAX_CART_ITEMS) {
            throw new BizException(ErrorCode.CART_LIMIT, "购物车最多容纳 " + MAX_CART_ITEMS + " 件商品");
        }
        CartItem item = new CartItem();
        item.setUserId(userId);
        item.setSkuId(sku.getSkuId());
        item.setSpuId(sku.getSpuId());
        item.setMerchantId(sku.getMerchantId());
        item.setShopId(sku.getShopId());
        item.setSkuName(sku.getSkuName());
        item.setSpecText(sku.getSpecText());
        item.setImage(sku.getImage());
        item.setPriceFen(sku.getSalePriceFen());
        item.setQty(request.getQty());
        item.setSelected(1);
        int reason = purchaseLimitChecker.invalidReason(sku);
        item.setInvalid(reason == 0 ? 0 : 1);
        item.setInvalidReason(reason);
        cartItemMapper.insert(item);
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQuantity(Long userId, Long cartId, Integer qty) {
        CartItem item = mustOwn(userId, cartId);
        if (qty > MAX_SINGLE_QTY) {
            throw new BizException(ErrorCode.CART_LIMIT, "单个商品数量不能超过 " + MAX_SINGLE_QTY + " 件");
        }
        item.setQty(qty);
        cartItemMapper.updateById(item);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long cartId) {
        // 逻辑删除值写入本行雪花 id（而非固定 1）：历史删除行 deleted 互不相同，
        // 配合 uk(user_id, sku_id, deleted) 支持删除后重新加购同一 SKU。
        int rows = cartItemMapper.update(null, new LambdaUpdateWrapper<CartItem>()
                .eq(CartItem::getId, cartId).eq(CartItem::getUserId, userId)
                .setSql("deleted = id"));
        if (rows == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "购物车商品不存在");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveToFavorite(Long userId, Long cartId) {
        CartItem item = mustOwn(userId, cartId);
        Long exists = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId).eq(Favorite::getSkuId, item.getSkuId()));
        if (exists == null || exists == 0) {
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setSkuId(item.getSkuId());
            favorite.setSpuId(item.getSpuId());
            favorite.setMerchantId(item.getMerchantId());
            favorite.setShopId(item.getShopId());
            favorite.setSkuName(item.getSkuName());
            favorite.setSpecText(item.getSpecText());
            favorite.setImage(item.getImage());
            favorite.setPriceFen(item.getPriceFen());
            favoriteMapper.insert(favorite);
        }
        // 契约：移入收藏只新增/幂等收藏关系，不删除购物车行（用户可在收藏夹与购物车同时保留，
        // 是否删除由用户显式调用删除接口决定；E2E 验收：收藏后购物车行仍可查）。
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void select(Long userId, CartSelectRequest request) {
        int selected = normalizeSelected(request.getSelected());
        if (request.getIds() != null && !request.getIds().isEmpty()) {
            cartItemMapper.update(null, new LambdaUpdateWrapper<CartItem>()
                    .eq(CartItem::getUserId, userId)
                    .in(CartItem::getId, request.getIds())
                    .set(CartItem::getSelected, selected));
        } else {
            selectAll(userId, selected, request.getShopId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void selectAll(Long userId, Integer selected, Long shopId) {
        int value = normalizeSelected(selected);
        LambdaUpdateWrapper<CartItem> wrapper = new LambdaUpdateWrapper<CartItem>()
                .eq(CartItem::getUserId, userId);
        if (shopId != null) {
            wrapper.eq(CartItem::getShopId, shopId);
        }
        wrapper.set(CartItem::getSelected, value);
        cartItemMapper.update(null, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void invert(Long userId) {
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId));
        for (CartItem item : items) {
            item.setSelected(item.getSelected() != null && item.getSelected() == 1 ? 0 : 1);
            cartItemMapper.updateById(item);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int clearInvalid(Long userId) {
        // deleted 置为各行自身 id，多行清理也不会在 uk(user_id, sku_id, deleted) 上撞键
        return cartItemMapper.update(null, new LambdaUpdateWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getInvalid, 1)
                .setSql("deleted = id"));
    }

    @Override
    public CartViewVO view(Long userId) {
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .orderByAsc(CartItem::getShopId)
                .orderByDesc(CartItem::getCreateTime));
        if (items.isEmpty()) {
            return CartViewVO.builder()
                    .shopGroups(List.of())
                    .totalCount(0).totalQty(0).invalidCount(0)
                    .allSelected(true).selectedAmountFen(0L)
                    .build();
        }
        Map<Long, SkuDTO> skuMap = loadSkus(items);
        List<CartItemVO> voList = new ArrayList<>(items.size());
        for (CartItem item : items) {
            SkuDTO sku = skuMap.get(item.getSkuId());
            int reason = purchaseLimitChecker.invalidReason(sku);
            int invalid = reason == 0 ? 0 : 1;
            // 失效状态变化落库（结算前刷新，design 5.4）
            if (!Objects.equals(item.getInvalid(), invalid)
                    || !Objects.equals(item.getInvalidReason(), reason)) {
                cartItemMapper.updateInvalid(item.getId(), userId, invalid, reason);
                item.setInvalid(invalid);
                item.setInvalidReason(reason);
            }
            long currentPrice = sku == null || sku.getSalePriceFen() == null ? 0L : sku.getSalePriceFen();
            boolean priceChanged = invalid == 0 && item.getPriceFen() != null
                    && item.getPriceFen() != currentPrice;
            voList.add(CartItemVO.builder()
                    .cartId(item.getId())
                    .skuId(item.getSkuId())
                    .spuId(item.getSpuId())
                    .merchantId(item.getMerchantId())
                    .shopId(item.getShopId())
                    .skuName(item.getSkuName())
                    .specText(item.getSpecText())
                    .image(item.getImage())
                    .addPriceFen(item.getPriceFen())
                    .currentPriceFen(currentPrice)
                    .qty(item.getQty())
                    .selected(item.getSelected())
                    .invalid(invalid)
                    .invalidReason(reason)
                    .priceChanged(priceChanged)
                    .build());
        }
        return buildView(voList);
    }

    private Map<Long, SkuDTO> loadSkus(List<CartItem> items) {
        List<Long> skuIds = items.stream().map(CartItem::getSkuId).distinct().toList();
        try {
            List<SkuDTO> skus = FeignResults.unwrap(productClient.listSkus(skuIds));
            if (skus == null) {
                return Map.of();
            }
            return skus.stream().collect(Collectors.toMap(SkuDTO::getSkuId, s -> s, (a, b) -> a));
        } catch (BizException e) {
            // 商品域暂不可用时按加购快照展示，不误标失效
            return Map.of();
        }
    }

    private CartViewVO buildView(List<CartItemVO> voList) {
        Map<Long, List<CartItemVO>> byShop = voList.stream()
                .collect(Collectors.groupingBy(CartItemVO::getShopId, LinkedHashMap::new, Collectors.toList()));
        List<CartShopGroupVO> groups = new ArrayList<>(byShop.size());
        int totalQty = 0;
        int invalidCount = 0;
        long selectedAmount = 0L;
        boolean allSelected = true;
        int selectableCount = 0;
        for (Map.Entry<Long, List<CartItemVO>> e : byShop.entrySet()) {
            List<CartItemVO> list = e.getValue();
            list.sort(Comparator.comparing(CartItemVO::getCartId));
            int selectedCount = 0;
            int selectedQty = 0;
            long groupAmount = 0L;
            int groupInvalid = 0;
            boolean groupAll = true;
            for (CartItemVO vo : list) {
                totalQty += vo.getQty();
                if (vo.getInvalid() == 1) {
                    groupInvalid++;
                    invalidCount++;
                    continue;
                }
                selectableCount++;
                if (vo.getSelected() != null && vo.getSelected() == 1) {
                    selectedCount++;
                    selectedQty += vo.getQty();
                    groupAmount += vo.getCurrentPriceFen() * vo.getQty();
                } else {
                    groupAll = false;
                    allSelected = false;
                }
            }
            selectedAmount += groupAmount;
            CartItemVO first = list.get(0);
            groups.add(CartShopGroupVO.builder()
                    .merchantId(first.getMerchantId())
                    .shopId(first.getShopId())
                    .items(list)
                    .allSelected(groupAll)
                    .selectedCount(selectedCount)
                    .selectedQty(selectedQty)
                    .selectedAmountFen(groupAmount)
                    .invalidCount(groupInvalid)
                    .build());
        }
        if (selectableCount == 0) {
            allSelected = true;
        }
        return CartViewVO.builder()
                .shopGroups(groups)
                .totalCount(voList.size())
                .totalQty(totalQty)
                .invalidCount(invalidCount)
                .allSelected(allSelected)
                .selectedAmountFen(selectedAmount)
                .build();
    }

    private CartItem mustOwn(Long userId, Long cartId) {
        CartItem item = cartItemMapper.selectById(cartId);
        if (item == null || !userId.equals(item.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "购物车商品不存在");
        }
        return item;
    }

    private int normalizeSelected(Integer selected) {
        if (selected == null || (selected != 0 && selected != 1)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "勾选标记必须为 0 或 1");
        }
        return selected;
    }
}
