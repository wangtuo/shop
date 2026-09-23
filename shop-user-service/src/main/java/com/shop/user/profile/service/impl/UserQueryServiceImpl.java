package com.shop.user.profile.service.impl;

import com.shop.api.user.dto.UserDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import com.shop.user.profile.service.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserQueryServiceImpl implements UserQueryService {

    private final UserMapper userMapper;

    @Override
    public User requireUser(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + userId);
        }
        return user;
    }

    @Override
    public UserDTO getUserDTO(Long userId) {
        User user = requireUser(userId);
        return UserDTO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .phone(user.getPhone())
                .userType(user.getUserType())
                .merchantId(user.getMerchantId())
                .level(user.getLevel())
                .status(user.getStatus())
                .growth(user.getGrowth())
                .build();
    }
}
