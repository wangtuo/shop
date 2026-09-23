package com.shop.framework.web;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录用户上下文，由网关注入的身份头解析而来。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private Long userId;
    private String userName;
    /** 0 普通用户 1 商户 2 平台运营 -1 游客，见 design.md 2.1.1 */
    private Integer userType;
    private Long merchantId;
}
