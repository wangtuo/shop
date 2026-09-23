package com.shop.user.account.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.user.enums.UserStatuses;
import com.shop.api.user.enums.UserTypes;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.user.account.dto.AdminCreateAccountRequest;
import com.shop.user.account.service.AccountService;
import com.shop.user.account.service.AdminAccountService;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台运营账号管理实现。开通逻辑与自助注册同构（BCrypt、唯一约束、初始化四类账户），
 * 但身份字段由服务端按通道强制写入，调用方无权指定。
 */
@Service
@RequiredArgsConstructor
public class AdminAccountServiceImpl implements AdminAccountService {

    private final UserMapper userMapper;
    private final AccountService accountService;
    private final IdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createMerchantAccount(AdminCreateAccountRequest req) {
        long userId = idGenerator.nextId();
        User user = buildUser(req, userId);
        user.setUserType(UserTypes.MERCHANT);
        // merchantId 绑定自身账号 ID；清算域入驻的 merchantId 即此值
        user.setMerchantId(userId);
        insert(user);
        accountService.initAccounts(user.getId());
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPlatformAccount(AdminCreateAccountRequest req) {
        long userId = idGenerator.nextId();
        User user = buildUser(req, userId);
        user.setUserType(UserTypes.PLATFORM);
        user.setMerchantId(null);
        insert(user);
        accountService.initAccounts(user.getId());
        return user.getId();
    }

    private User buildUser(AdminCreateAccountRequest req, long userId) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, req.getPhone())) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "手机号已存在");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername())) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        User user = new User();
        user.setId(userId);
        user.setUsername(req.getUsername());
        user.setPhone(req.getPhone());
        user.setPassword(BCrypt.hashpw(req.getPassword()));
        user.setNickname(req.getNickname() == null || req.getNickname().isBlank()
                ? req.getUsername() : req.getNickname());
        user.setStatus(UserStatuses.NORMAL);
        user.setGrowth(0L);
        user.setLevel(0);
        user.setContinuousDays(0);
        return user;
    }

    private void insert(User user) {
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "手机号或用户名已存在");
        }
    }
}
