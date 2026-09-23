package com.shop.user.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 平台运营开通账号请求（商户/运营账号，C-2 修复配套正规通道）。
 * 仅 {@code userType=2} 平台运营登录后可调，不接受任何客户端指定身份字段。
 */
@Data
public class AdminCreateAccountRequest {

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
