package com.shop.user.profile.service;

import com.shop.api.user.dto.UserDTO;
import com.shop.user.profile.entity.User;

/**
 * 用户资料查询：C 端 /users/me 与内部 getUser 共用。
 */
public interface UserQueryService {

    /** 按 ID 查用户实体，不存在抛 NOT_FOUND */
    User requireUser(Long userId);

    /** 按 ID 组装对外 UserDTO（手机号脱敏） */
    UserDTO getUserDTO(Long userId);
}
