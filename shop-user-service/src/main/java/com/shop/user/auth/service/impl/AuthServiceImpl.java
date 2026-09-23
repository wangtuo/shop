package com.shop.user.auth.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.user.enums.UserStatuses;
import com.shop.api.user.enums.UserTypes;
import com.shop.api.user.event.UserRegisteredEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.framework.security.JwtService;
import com.shop.user.account.service.AccountService;
import com.shop.user.auth.dto.BootstrapAdminRequest;
import com.shop.user.auth.dto.LoginRequest;
import com.shop.user.auth.dto.LoginResponse;
import com.shop.user.auth.dto.RegisterRequest;
import com.shop.user.auth.security.LoginLockService;
import com.shop.user.auth.service.AuthService;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务实现。
 *
 * <p>注册（C-2 安全策略）：自助注册通道只能创建消费者账号（userType=0）。
 * 请求体中的 userType=1（商户）直接拒绝，商户账号只能由平台入驻审核流程创建；
 * merchantId 一律忽略，杜绝自助绑定任意商户身份。
 *
 * <p>登录：用户名或手机号 + 密码；冻结/注销账号禁止登录；JWT 含 userType，商户（userType=1）带 merchantId。
 * 登录失败按用户名 + IP 双维度计数锁定（见 {@link LoginLockService}，M-2）。
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final AccountService accountService;
    private final JwtService jwtService;
    private final IdGenerator idGenerator;
    private final LoginLockService loginLockService;
    private final OutboxPublisher outboxPublisher;

    /**
     * 首个平台账号引导令牌。dev 默认固定开发值；prod profile 文档段将默认置空
     * （功能关闭），必须由运维显式注入 SHOP_ADMIN_BOOTSTRAP_TOKEN 才可用。
     */
    @Value("${shop.security.admin-bootstrap-token:dev-local-only-bootstrap-token}")
    private String adminBootstrapToken;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(RegisterRequest req) {
        // C-2：自助注册强制消费者身份。商户账号请走平台入驻流程，运营账号更不允许自助开通。
        Integer requestedType = req.getUserType();
        if (requestedType != null && requestedType == UserTypes.MERCHANT) {
            throw new BizException(ErrorCode.PARAM_INVALID, "商户账号请通过平台入驻流程开通");
        }
        if (requestedType != null && requestedType != UserTypes.NORMAL) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的用户类型，自助注册仅允许消费者账号");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, req.getPhone())) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "手机号已注册");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername())) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }

        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername(req.getUsername());
        user.setPhone(req.getPhone());
        user.setPassword(BCrypt.hashpw(req.getPassword()));
        user.setNickname(req.getNickname() == null || req.getNickname().isBlank()
                ? req.getUsername() : req.getNickname());
        // 服务端强制：无论请求体携带什么 userType/merchantId，注册结果只能是无商户绑定的消费者
        user.setUserType(UserTypes.NORMAL);
        user.setMerchantId(null);
        user.setStatus(UserStatuses.NORMAL);
        user.setGrowth(0L);
        user.setLevel(0);
        user.setContinuousDays(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "手机号或用户名已存在");
        }
        accountService.initAccounts(user.getId());
        // B6-a：注册事件走同事务 outbox，随注册一起提交（冲突回滚路径到不了这里，无幽灵消息）。
        // 严禁事务内 Feign 调营销；事件只放 userId/时间/userType，不含手机号。
        UserRegisteredEvent event = UserRegisteredEvent.builder()
                .userId(user.getId())
                .registerTime(System.currentTimeMillis())
                .userType(UserTypes.NORMAL)
                .build();
        event.setBizNo("REGISTER:" + user.getId());
        outboxPublisher.publish(MqTopics.USER_REGISTERED, null, event, "REGISTER:" + user.getId());
        return user.getId();
    }

    @Override
    public LoginResponse login(LoginRequest req, String clientIp) {
        // M-2：锁定中（用户名或 IP 维度）直接拒绝，不再校验密码
        loginLockService.assertNotLocked(req.getAccount(), clientIp);

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getAccount()));
        if (user == null) {
            user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, req.getAccount()));
        }
        if (user == null || !BCrypt.checkpw(req.getPassword(), user.getPassword())) {
            loginLockService.recordFailure(req.getAccount(), clientIp);
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (user.getStatus() == UserStatuses.FROZEN) {
            throw new BizException(ErrorCode.FORBIDDEN, "账户已冻结，禁止登录");
        }
        if (user.getStatus() == UserStatuses.CANCELLED) {
            throw new BizException(ErrorCode.FORBIDDEN, "账户已注销，禁止登录");
        }
        // 登录成功：清零失败计数
        loginLockService.clearLock(req.getAccount(), clientIp);
        String token = jwtService.issue(user.getId(), user.getUsername(), user.getUserType(),
                user.getUserType().equals(UserTypes.MERCHANT) ? user.getMerchantId() : null);
        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .userType(user.getUserType())
                .merchantId(user.getUserType().equals(UserTypes.MERCHANT) ? user.getMerchantId() : null)
                .level(user.getLevel())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long bootstrapAdmin(BootstrapAdminRequest req, String bootstrapToken) {
        // prod 下令牌默认空（功能关闭）；显式注入后必须严格等值
        if (adminBootstrapToken == null || adminBootstrapToken.isBlank()
                || !adminBootstrapToken.equals(bootstrapToken)) {
            throw new BizException(ErrorCode.FORBIDDEN, "引导令牌缺失或无效");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUserType, UserTypes.PLATFORM)) > 0) {
            // 已有平台账号即永久关闭本通道，防止二次引导
            throw new BizException(ErrorCode.CONFLICT, "平台账号已存在，引导通道已关闭");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, req.getPhone())) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "手机号已注册");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername())) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername(req.getUsername());
        user.setPhone(req.getPhone());
        user.setPassword(BCrypt.hashpw(req.getPassword()));
        user.setNickname(req.getNickname() == null || req.getNickname().isBlank()
                ? req.getUsername() : req.getNickname());
        user.setUserType(UserTypes.PLATFORM);
        user.setMerchantId(null);
        user.setStatus(UserStatuses.NORMAL);
        user.setGrowth(0L);
        user.setLevel(0);
        user.setContinuousDays(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "手机号或用户名已存在");
        }
        accountService.initAccounts(user.getId());
        return user.getId();
    }
}
