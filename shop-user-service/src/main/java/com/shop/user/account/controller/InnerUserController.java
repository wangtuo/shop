package com.shop.user.account.controller;

import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.AddressDTO;
import com.shop.api.user.dto.AmountCommand;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.GrowthCommand;
import com.shop.api.user.dto.PointsDeductCommand;
import com.shop.api.user.dto.PointsLockCommand;
import com.shop.api.user.dto.PointsRefundCommand;
import com.shop.api.user.dto.PointsReleaseCommand;
import com.shop.api.user.dto.UserDTO;
import com.shop.api.user.dto.UserLevelDTO;
import com.shop.api.user.enums.AccountTypes;
import com.shop.common.result.Result;
import com.shop.framework.web.Anonymous;
import com.shop.user.account.service.AccountService;
import com.shop.user.account.service.GrowthService;
import com.shop.user.address.service.AddressService;
import com.shop.user.profile.service.UserQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户域内部接口（/inner/user），与 {@link UserClient} 的路径/方法逐字对齐。
 * 仅服务间 Feign 调用可达（网关不对外暴露 /inner/**），故类级 {@link Anonymous}。
 * 全部写操作以 bizNo 幂等。
 */
@Anonymous
@RestController
@RequestMapping("/inner/user")
@RequiredArgsConstructor
public class InnerUserController implements UserClient {

    private final UserQueryService userQueryService;
    private final GrowthService growthService;
    private final AddressService addressService;
    private final AccountService accountService;

    @Override
    @GetMapping("/get")
    @com.fasterxml.jackson.annotation.JsonView(com.shop.common.jackson.JsonViews.Internal.class)
    public Result<UserDTO> getUser(@RequestParam("userId") Long userId) {
        return Result.success(userQueryService.getUserDTO(userId));
    }

    @Override
    @GetMapping("/level")
    public Result<UserLevelDTO> getLevel(@RequestParam("userId") Long userId) {
        return Result.success(growthService.getLevel(userId));
    }

    @Override
    @GetMapping("/address")
    public Result<AddressDTO> getAddress(@RequestParam("addressId") Long addressId) {
        return Result.success(addressService.toDto(addressService.getByIdInternal(addressId)));
    }

    @Override
    @PostMapping("/points/lock")
    public Result<Void> lockPoints(@Valid @RequestBody PointsLockCommand cmd) {
        accountService.lockPoints(cmd);
        return Result.success();
    }

    @Override
    @PostMapping("/points/deduct")
    public Result<Void> deductPoints(@Valid @RequestBody PointsDeductCommand cmd) {
        accountService.deductPoints(cmd);
        return Result.success();
    }

    @Override
    @PostMapping("/points/release")
    public Result<Void> releasePoints(@Valid @RequestBody PointsReleaseCommand cmd) {
        accountService.releasePoints(cmd);
        return Result.success();
    }

    @Override
    @PostMapping("/points/refund")
    public Result<Void> refundPoints(@Valid @RequestBody PointsRefundCommand cmd) {
        accountService.refundPoints(cmd);
        return Result.success();
    }

    @Override
    @PostMapping("/points/grant")
    public Result<Void> grantPoints(@Valid @RequestBody GrantPointsCommand cmd) {
        accountService.grantPoints(cmd);
        return Result.success();
    }

    @Override
    @PostMapping("/growth/add")
    public Result<Void> addGrowth(@Valid @RequestBody GrowthCommand cmd) {
        growthService.addGrowth(cmd);
        return Result.success();
    }

    @Override
    @PostMapping("/balance/debit")
    public Result<Void> debitBalance(@Valid @RequestBody AmountCommand cmd) {
        accountService.debitMoney(cmd, AccountTypes.BALANCE);
        return Result.success();
    }

    @Override
    @PostMapping("/balance/credit")
    public Result<Void> creditBalance(@Valid @RequestBody AmountCommand cmd) {
        accountService.creditMoney(cmd);
        return Result.success();
    }

    @Override
    @PostMapping("/gift/debit")
    public Result<Void> debitGift(@Valid @RequestBody AmountCommand cmd) {
        accountService.debitMoney(cmd, AccountTypes.GIFT);
        return Result.success();
    }
}
