package com.shop.user.auth;

import cn.hutool.crypto.digest.BCrypt;
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
import com.shop.user.auth.dto.LoginRequest;
import com.shop.user.auth.dto.LoginResponse;
import com.shop.user.auth.dto.RegisterRequest;
import com.shop.user.auth.security.LoginLockService;
import com.shop.user.auth.service.impl.AuthServiceImpl;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册（C-2：仅消费者、商户自助注册拒绝、merchantId 忽略）、
 * 登录（M-2：失败计数锁定、成功清零；JWT 含 userType/merchantId、冻结态禁止登录）用例。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceImplTest {

    private static final String IP = "10.0.0.9";

    @Mock
    private UserMapper userMapper;
    @Mock
    private AccountService accountService;
    @Mock
    private JwtService jwtService;
    @Mock
    private IdGenerator idGenerator;
    @Mock
    private LoginLockService loginLockService;
    @Mock
    private OutboxPublisher outboxPublisher;
    @InjectMocks
    private AuthServiceImpl authService;

    private RegisterRequest register(String username, String phone, String password,
                                     Integer userType, Long merchantId) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setPhone(phone);
        r.setPassword(password);
        r.setUserType(userType);
        r.setMerchantId(merchantId);
        return r;
    }

    @Test
    void register_普通用户_密码BCrypt加盐并初始化三账户() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        authService.register(register("alice", "13800001111", "secret123", null, null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals(UserTypes.NORMAL, saved.getUserType());
        assertEquals(UserStatuses.NORMAL, saved.getStatus());
        assertNull(saved.getMerchantId());
        assertTrue(BCrypt.checkpw("secret123", saved.getPassword()));
        assertTrue(saved.getPassword().startsWith("$2"));
        verify(accountService).initAccounts(saved.getId());
    }

    @Test
    void register_匿名携带商户类型与merchantId_拒绝且拿不到商户身份() {
        BizException ex = assertThrows(BizException.class,
                () -> authService.register(register("shop1", "13800002222", "secret123",
                        UserTypes.MERCHANT, 888L)));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("商户账号请通过平台入驻流程开通"));
        // 拒绝发生在任何落库/查重之前
        verify(userMapper, never()).insert(any());
        verify(userMapper, never()).selectCount(any());
    }

    @Test
    void register_消费者请求携带merchantId_被忽略且不产生绑定() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        authService.register(register("bob", "13800009999", "secret123", UserTypes.NORMAL, 999L));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals(UserTypes.NORMAL, saved.getUserType());
        assertNull(saved.getMerchantId());
    }

    @Test
    void register_省略userType但携带merchantId_仍为无绑定消费者() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        authService.register(register("carol", "13800008888", "secret123", null, 666L));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals(UserTypes.NORMAL, captor.getValue().getUserType());
        assertNull(captor.getValue().getMerchantId());
    }

    @Test
    void register_平台账号_禁止自助注册() {
        assertThrows(BizException.class,
                () -> authService.register(register("root", "13800003333", "secret123",
                        UserTypes.PLATFORM, null)));
        verify(userMapper, never()).insert(any());
    }

    @Test
    void register_手机号已注册_抛冲突() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class,
                () -> authService.register(register("bob", "13800001111", "secret123", null, null)));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void register_成功_同事务outbox发注册事件_bizNo为REGISTER用户id() {
        when(idGenerator.nextId()).thenReturn(5001L);
        when(userMapper.selectCount(any())).thenReturn(0L);

        Long id = authService.register(register("newbie", "13900006666", "secret123", null, null));
        assertEquals(5001L, id);

        ArgumentCaptor<UserRegisteredEvent> eventCaptor = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(outboxPublisher).publish(eq(MqTopics.USER_REGISTERED), isNull(),
                eventCaptor.capture(), eq("REGISTER:5001"));
        UserRegisteredEvent event = eventCaptor.getValue();
        assertEquals(5001L, event.getUserId());
        assertEquals(UserTypes.NORMAL, event.getUserType());
        assertNotNull(event.getRegisterTime());
        assertTrue(event.getRegisterTime() > 0);
    }

    @Test
    void register_插入冲突_抛冲突且不写outbox不初始化账户() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_phone"));

        BizException ex = assertThrows(BizException.class,
                () -> authService.register(register("dup", "13900007777", "secret123", null, null)));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(outboxPublisher, never()).publish(anyString(), any(), any(), any());
        verify(accountService, never()).initAccounts(any());
    }

    private User storedUser(String username, String phone, String rawPwd, int type, Long merchantId, int status) {
        User u = new User();
        u.setId(7001L);
        u.setUsername(username);
        u.setPhone(phone);
        u.setPassword(BCrypt.hashpw(rawPwd));
        u.setNickname(username);
        u.setUserType(type);
        u.setMerchantId(merchantId);
        u.setStatus(status);
        u.setLevel(0);
        return u;
    }

    private LoginRequest login(String account, String pwd) {
        LoginRequest r = new LoginRequest();
        r.setAccount(account);
        r.setPassword(pwd);
        return r;
    }

    @Test
    void login_普通用户_签发不含merchantId的JWT并清零失败计数() {
        when(userMapper.selectOne(any())).thenReturn(storedUser("alice", "13800001111", "secret123",
                UserTypes.NORMAL, null, UserStatuses.NORMAL));
        when(jwtService.issue(eq(7001L), eq("alice"), eq(UserTypes.NORMAL), isNull())).thenReturn("token-1");

        LoginResponse resp = authService.login(login("alice", "secret123"), IP);

        assertEquals("token-1", resp.getToken());
        assertEquals(UserTypes.NORMAL, resp.getUserType());
        assertNull(resp.getMerchantId());
        verify(loginLockService).assertNotLocked("alice", IP);
        verify(loginLockService).clearLock("alice", IP);
        verify(loginLockService, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void login_商户用户_JWT携带merchantId() {
        when(userMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(storedUser("shop1", "13800002222", "secret123",
                        UserTypes.MERCHANT, 888L, UserStatuses.NORMAL));
        when(jwtService.issue(7001L, "shop1", UserTypes.MERCHANT, 888L)).thenReturn("token-2");

        LoginResponse resp = authService.login(login("13800002222", "secret123"), IP);
        assertEquals("token-2", resp.getToken());
        assertEquals(888L, resp.getMerchantId());
    }

    @Test
    void login_密码错误_记一次失败并抛未授权() {
        when(userMapper.selectOne(any())).thenReturn(storedUser("alice", "13800001111", "secret123",
                0, null, 0));
        BizException ex = assertThrows(BizException.class,
                () -> authService.login(login("alice", "wrong"), IP));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        verify(loginLockService).recordFailure("alice", IP);
        verify(loginLockService, never()).clearLock(anyString(), anyString());
        verify(jwtService, never()).issue(any(), any(), anyInt(), any());
    }

    @Test
    void login_账号不存在_记一次失败并抛未授权() {
        when(userMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> authService.login(login("ghost", "secret123"), IP));
        verify(loginLockService).recordFailure("ghost", IP);
    }

    @Test
    void login_连续5次失败后_第6次即使密码正确也被锁定拒绝() {
        when(userMapper.selectOne(any())).thenReturn(storedUser("alice", "13800001111", "secret123",
                UserTypes.NORMAL, null, UserStatuses.NORMAL));

        // 先制造 5 次错误密码（期间未锁定）
        for (int i = 0; i < 5; i++) {
            assertThrows(BizException.class, () -> authService.login(login("alice", "wrong"), IP));
        }
        verify(loginLockService, times(5)).recordFailure("alice", IP);

        // 第 6 次锁定生效：锁拦截发生在密码校验之前，即便密码正确也拒绝
        doThrow(new BizException(ErrorCode.TOO_MANY_REQUESTS, "已临时锁定15分钟"))
                .when(loginLockService).assertNotLocked(eq("alice"), eq(IP));
        BizException ex = assertThrows(BizException.class,
                () -> authService.login(login("alice", "secret123"), IP));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        verify(jwtService, never()).issue(any(), any(), anyInt(), any());
        verify(loginLockService, never()).clearLock(anyString(), anyString());
    }

    @Test
    void login_冻结账号_禁止登录() {
        when(userMapper.selectOne(any())).thenReturn(storedUser("frozen", "13800004444", "secret123",
                0, null, UserStatuses.FROZEN));
        BizException ex = assertThrows(BizException.class,
                () -> authService.login(login("frozen", "secret123"), IP));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void login_注销账号_禁止登录() {
        when(userMapper.selectOne(any())).thenReturn(storedUser("cancel", "13800005555", "secret123",
                0, null, UserStatuses.CANCELLED));
        assertThrows(BizException.class, () -> authService.login(login("cancel", "secret123"), IP));
    }

    private com.shop.user.auth.dto.BootstrapAdminRequest bootstrap(String username, String phone) {
        com.shop.user.auth.dto.BootstrapAdminRequest r = new com.shop.user.auth.dto.BootstrapAdminRequest();
        r.setUsername(username);
        r.setPhone(phone);
        r.setPassword("secret123");
        r.setNickname(username);
        return r;
    }

    @Test
    void bootstrapAdmin_令牌错误_拒绝且不落库() {
        org.springframework.test.util.ReflectionTestUtils
                .setField(authService, "adminBootstrapToken", "good-token");
        BizException ex = assertThrows(BizException.class,
                () -> authService.bootstrapAdmin(bootstrap("root", "13800007777"), "bad-token"));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(userMapper, never()).insert(any());
        verify(userMapper, never()).selectCount(any());
    }

    @Test
    void bootstrapAdmin_空令牌_拒绝() {
        org.springframework.test.util.ReflectionTestUtils
                .setField(authService, "adminBootstrapToken", "  ");
        assertThrows(BizException.class,
                () -> authService.bootstrapAdmin(bootstrap("root", "13800007777"), "  "));
        verify(userMapper, never()).insert(any());
    }

    @Test
    void bootstrapAdmin_无平台账号且令牌正确_创建平台账号() {
        org.springframework.test.util.ReflectionTestUtils
                .setField(authService, "adminBootstrapToken", "good-token");
        when(idGenerator.nextId()).thenReturn(9001L);
        when(userMapper.selectCount(any())).thenReturn(0L);

        Long id = authService.bootstrapAdmin(bootstrap("root", "13800007777"), "good-token");
        assertEquals(9001L, id);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals(UserTypes.PLATFORM, captor.getValue().getUserType());
        assertNull(captor.getValue().getMerchantId());
        verify(accountService).initAccounts(9001L);
    }

    @Test
    void bootstrapAdmin_已有平台账号_引导通道永久关闭() {
        org.springframework.test.util.ReflectionTestUtils
                .setField(authService, "adminBootstrapToken", "good-token");
        when(userMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> authService.bootstrapAdmin(bootstrap("root2", "13800007778"), "good-token"));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(userMapper, never()).insert(any());
    }
}
