package com.shop.user.auth.controller;

import com.shop.common.result.Result;
import com.shop.framework.ratelimit.RateLimit;
import com.shop.framework.web.Anonymous;
import com.shop.user.auth.dto.BootstrapAdminRequest;
import com.shop.user.auth.dto.LoginRequest;
import com.shop.user.auth.dto.LoginResponse;
import com.shop.user.auth.dto.RegisterRequest;
import com.shop.user.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端认证 HTTP 接口。仅注册/登录免登录（{@link Anonymous}）。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 注册：手机号 + 用户名 + 密码（手机号唯一，BCrypt 加盐）；仅能注册消费者账号。
     * M-2 防刷：匿名接口按客户端 IP 限流，单 IP 每分钟最多 5 次注册，防批量撞库注册。
     */
    @Anonymous
    @RateLimit(prefix = "auth:register", permits = 5, windowSeconds = 60,
            permitsConfig = "shop.ratelimit.auth.register.permits",
            windowConfig = "shop.ratelimit.auth.register.window-seconds",
            message = "注册操作过于频繁，请稍后再试")
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    /**
     * 登录：签发 JWT（含 userType；冻结态禁止登录；失败 5 次锁定 15 分钟）。
     * M-2 防刷：账号失败锁定防爆破（服务层）+ 匿名入口按客户端 IP 限流（单 IP 每分钟 20 次）
     * 防跨账号撞库/凭证填充。
     */
    @Anonymous
    @RateLimit(prefix = "auth:login", permits = 20, windowSeconds = 60,
            permitsConfig = "shop.ratelimit.auth.login.permits",
            windowConfig = "shop.ratelimit.auth.login.window-seconds",
            message = "登录尝试过于频繁，请稍后再试")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        return Result.success(authService.login(request, resolveClientIp(httpRequest)));
    }

    /**
     * 一次性引导首个平台运营账号。
     * 匿名可达，但必须携带正确的 X-Bootstrap-Token，且库中尚无平台账号；
     * prod profile 下引导令牌默认空，本接口等同关闭。
     */
    @Anonymous
    @PostMapping("/bootstrap-admin")
    public Result<Long> bootstrapAdmin(@Valid @RequestBody BootstrapAdminRequest request,
                                       @RequestHeader(value = "X-Bootstrap-Token", required = false)
                                       String bootstrapToken) {
        return Result.success(authService.bootstrapAdmin(request, bootstrapToken));
    }

    /**
     * 取客户端 IP：优先网关/反向代理写入的 X-Forwarded-For（首个地址）、X-Real-IP，
     * 最后回退 TCP 对端地址。
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
