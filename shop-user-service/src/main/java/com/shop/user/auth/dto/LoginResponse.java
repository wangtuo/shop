package com.shop.user.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录成功响应：JWT 与简要身份信息。商户 userType=1 时带 merchantId。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private Long userId;
    private String username;
    private String nickname;
    private Integer userType;
    private Long merchantId;
    private Integer level;
}
