package com.shop.user.auth.service;

import com.shop.user.auth.dto.BootstrapAdminRequest;
import com.shop.user.auth.dto.LoginRequest;
import com.shop.user.auth.dto.LoginResponse;
import com.shop.user.auth.dto.RegisterRequest;

/**
 * 认证服务：注册（手机号唯一、BCrypt 加盐）、登录签发 JWT（冻结态禁止登录）。
 */
public interface AuthService {

    /** 注册并初始化三个账户，返回用户 ID。自助注册只能创建消费者账号。 */
    Long register(RegisterRequest request);

    /**
     * 登录：校验密码与账户状态，签发 JWT（含 userType，商户带 merchantId）。
     *
     * @param request  登录请求
     * @param clientIp 客户端 IP（用于 IP 维度登录失败锁定，可为 null）
     */
    LoginResponse login(LoginRequest request, String clientIp);

    /**
     * 一次性引导首个平台运营账号：引导令牌正确且库中尚无平台账号时才允许。
     *
     * @param request       账号信息
     * @param bootstrapToken 请求头 X-Bootstrap-Token 携带的引导令牌
     * @return 新平台运营用户 ID
     */
    Long bootstrapAdmin(BootstrapAdminRequest request, String bootstrapToken);
}
