package com.shop.user.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 首个平台运营账号引导请求（一次性）。
 *
 * <p>平台账号不允许自助注册（见 {@link RegisterRequest} 的 C-2 策略），
 * 系统首个平台账号只能通过引导接口创建：请求必须携带与服务端配置
 * {@code shop.security.admin-bootstrap-token} 一致的 X-Bootstrap-Token，
 * 且库中不存在任何平台账号时才允许执行（幂等防重）。
 * prod profile 下引导令牌默认为空（功能关闭），需运维显式注入才可用。
 */
@Data
public class BootstrapAdminRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度需在3~32位之间")
    private String username;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在6~64位之间")
    private String password;

    /** 昵称（可选，默认用用户名） */
    private String nickname;
}
