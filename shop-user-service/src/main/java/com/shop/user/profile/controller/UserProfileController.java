package com.shop.user.profile.controller;

import com.shop.api.user.dto.UserDTO;
import com.shop.api.user.dto.UserLevelDTO;
import com.shop.common.result.Result;
import com.shop.framework.web.UserContext;
import com.shop.user.account.service.GrowthService;
import com.shop.user.profile.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端用户资料：/users/me、/users/level（我的等级）。
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserQueryService userQueryService;
    private final GrowthService growthService;

    /** 当前登录用户资料（手机号脱敏） */
    @GetMapping("/me")
    public Result<UserDTO> me() {
        return Result.success(userQueryService.getUserDTO(UserContext.getUserId()));
    }

    /** 我的会员等级、折扣与积分倍率 */
    @GetMapping("/level")
    public Result<UserLevelDTO> level() {
        return Result.success(growthService.getLevel(UserContext.getUserId()));
    }
}
