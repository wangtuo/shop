package com.shop.order.cart.controller;

import com.shop.common.result.Result;
import com.shop.framework.web.UserContext;
import com.shop.order.cart.dto.CartAddRequest;
import com.shop.order.cart.dto.CartSelectRequest;
import com.shop.order.cart.dto.CartUpdateRequest;
import com.shop.order.cart.dto.CartViewVO;
import com.shop.order.cart.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车 HTTP（design 5.4）：加购不锁库存，最多 99 个 SKU 条目。
 */
@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /** 加入购物车 */
    @PostMapping
    public Result<Long> add(@Valid @RequestBody CartAddRequest request) {
        return Result.success(cartService.add(UserContext.getUserId(), request));
    }

    /** 修改数量 */
    @PutMapping("/{cartId}")
    public Result<Void> updateQuantity(@PathVariable Long cartId,
                                       @Valid @RequestBody CartUpdateRequest request) {
        cartService.updateQuantity(UserContext.getUserId(), cartId, request.getQty());
        return Result.success();
    }

    /** 删除条目 */
    @DeleteMapping("/{cartId}")
    public Result<Void> delete(@PathVariable Long cartId) {
        cartService.delete(UserContext.getUserId(), cartId);
        return Result.success();
    }

    /** 收藏（加入收藏夹，购物车行保留；是否移除由用户另行删除） */
    @PostMapping("/{cartId}/favorite")
    public Result<Void> favorite(@PathVariable Long cartId) {
        cartService.moveToFavorite(UserContext.getUserId(), cartId);
        return Result.success();
    }

    /** 勾选/取消勾选（可按条目/店铺） */
    @PutMapping("/select")
    public Result<Void> select(@Valid @RequestBody CartSelectRequest request) {
        cartService.select(UserContext.getUserId(), request);
        return Result.success();
    }

    /** 全选/全不选（可限定店铺） */
    @PutMapping("/select-all")
    public Result<Void> selectAll(@RequestParam("selected") Integer selected,
                                  @RequestParam(value = "shopId", required = false) Long shopId) {
        cartService.selectAll(UserContext.getUserId(), selected, shopId);
        return Result.success();
    }

    /** 反选 */
    @PutMapping("/invert")
    public Result<Void> invert() {
        cartService.invert(UserContext.getUserId());
        return Result.success();
    }

    /** 一键清理失效商品，返回清理条数 */
    @DeleteMapping("/invalid")
    public Result<Integer> clearInvalid() {
        return Result.success(cartService.clearInvalid(UserContext.getUserId()));
    }

    /** 按店铺分组视图 */
    @GetMapping
    public Result<CartViewVO> view() {
        return Result.success(cartService.view(UserContext.getUserId()));
    }
}
