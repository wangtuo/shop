package com.shop.user.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求：手机号 + 用户名 + 密码（BCrypt 加盐存储，手机号唯一）。
 *
 * <p>安全策略（C-2）：自助注册通道仅能创建消费者账号（userType=0）。
 * {@code userType=1}（商户）一律拒绝（商户账号请通过平台入驻流程开通）；
 * {@code merchantId} 由服务端忽略，任何情况下都不会绑定到自助注册账号。
 * 两字段仅为兼容旧请求体保留，服务端不再信任。
 */
@Data
public class RegisterRequest {

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

    /**
     * 用户类型（客户端不可信）：自助注册只接受 0 消费者；1 商户直接拒绝；
     * 商户账号只能由平台入驻审核流程在服务端创建。
     */
    private Integer userType;

    /** 商户 ID（客户端不可信）：自助注册一律忽略，不做任何绑定。 */
    private Long merchantId;
}
