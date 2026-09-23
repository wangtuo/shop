package com.shop.user.address.controller;

import com.shop.common.result.PageQuery;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.web.UserContext;
import com.shop.user.address.dto.AddressSaveRequest;
import com.shop.user.address.entity.UserAddress;
import com.shop.user.address.service.AddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端收货地址 HTTP 接口（/users/addresses）。
 */
@RestController
@RequestMapping("/users/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    /** 新增地址（最多 20 条） */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody AddressSaveRequest request) {
        return Result.success(addressService.create(UserContext.getUserId(), request));
    }

    /** 修改地址（归属校验） */
    @PutMapping("/{addressId}")
    public Result<Void> update(@PathVariable("addressId") Long addressId,
                               @Valid @RequestBody AddressSaveRequest request) {
        addressService.update(UserContext.getUserId(), addressId, request);
        return Result.success();
    }

    /** 删除地址（归属校验） */
    @DeleteMapping("/{addressId}")
    public Result<Void> delete(@PathVariable("addressId") Long addressId) {
        addressService.delete(UserContext.getUserId(), addressId);
        return Result.success();
    }

    /** 地址明细分页（默认地址优先） */
    @GetMapping
    public Result<PageResult<UserAddress>> page(PageQuery query) {
        return Result.success(addressService.page(UserContext.getUserId(), query));
    }

    /** 查询单条地址（归属校验） */
    @GetMapping("/{addressId}")
    public Result<UserAddress> detail(@PathVariable("addressId") Long addressId) {
        return Result.success(addressService.requireOwned(UserContext.getUserId(), addressId));
    }
}
