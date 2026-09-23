package com.shop.user.signin.controller;

import com.shop.common.result.Result;
import com.shop.framework.web.UserContext;
import com.shop.user.signin.dto.SignInResult;
import com.shop.user.signin.service.SignInService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端签到：POST /users/sign-in，返回今日所得积分、连签天数与里程碑成长值。
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class SignInController {

    private final SignInService signInService;

    @PostMapping("/sign-in")
    public Result<SignInResult> signIn() {
        return Result.success(signInService.sign(UserContext.getUserId()));
    }
}
