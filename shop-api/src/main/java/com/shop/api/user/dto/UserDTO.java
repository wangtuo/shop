package com.shop.api.user.dto;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shop.common.jackson.JsonViews;
import com.shop.common.jackson.PhoneMaskingSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户基础信息 DTO（用户域对外契约，字段自包含，禁止引用其他域包）。
 *
 * <p>userType：-1 游客 / 0 普通用户 / 1 商户用户 / 2 平台运营（见 {@code UserTypes}）。
 * <br>status：0 正常 / 1 冻结 / 2 注销（见 {@code UserStatuses}）。
 *
 * <p>规则来源：design.md 2.1.1 用户类型。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID（雪花 ID） */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /**
     * 手机号（加密/脱敏存储，按场景展示）。
     * 对外默认序列化脱敏（前 3 后 4）；内部 Feign /inner/** 经 {@link JsonViews.Internal} 视图取明文。
     */
    @JsonView(JsonViews.Internal.class)
    @JsonSerialize(using = PhoneMaskingSerializer.class)
    private String phone;

    /** 用户类型：-1 游客 0 普通 1 商户 2 平台运营 */
    private Integer userType;

    /**
     * 商户 ID（仅 userType=1 商户用户有值，本系统一店一商户，亦等于 shopId）。
     * 属于用户本人身份信息，随 /users/me 对持有者本人返回；不可由外部头伪造（以 JWT mid 为准）。
     */
    private Long merchantId;

    /** 会员等级：L0=0 ... L4=4 */
    private Integer level;

    /** 账户状态：0 正常 1 冻结 2 注销 */
    private Integer status;

    /** 成长值（消费 1 元 = 1，另有评价/晒单/签到奖励） */
    private Long growth;
}
