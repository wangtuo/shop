package com.shop.user.auth;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.user.auth.security.LoginLockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录防刷计数（M-2）Lua 原子脚本调用与 key 规则单测。
 */
@ExtendWith(MockitoExtension.class)
class LoginLockServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private LoginLockService loginLockService;

    @Test
    void 未达阈值_放行() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(0L);
        loginLockService.assertNotLocked("alice", "10.0.0.1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 达到阈值_抛频繁请求异常且key为用户名加IP双维度() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class,
                () -> loginLockService.assertNotLocked("alice", "10.0.0.1"));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keys.capture(), any());
        assertEquals("auth:login:fail:user:alice", keys.getValue().get(0));
        assertEquals("auth:login:fail:ip:10.0.0.1", keys.getValue().get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 记录失败_原子脚本传入双key与15分钟毫秒TTL() {
        loginLockService.recordFailure("alice", "10.0.0.1");
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> ttl = ArgumentCaptor.forClass(Object.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keys.capture(), ttl.capture());
        assertEquals(2, keys.getValue().size());
        assertEquals(String.valueOf(15L * 60 * 1000), ttl.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void IP为空_仅使用用户名维度key() {
        loginLockService.recordFailure("alice", null);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keys.capture(), any());
        assertEquals(1, keys.getValue().size());
        assertTrue(keys.getValue().get(0).startsWith("auth:login:fail:user:"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 登录成功_删除双维度计数key() {
        loginLockService.clearLock("alice", "10.0.0.1");
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).delete(keys.capture());
        assertEquals(2, keys.getValue().size());
        assertTrue(keys.getValue().contains("auth:login:fail:user:alice"));
        assertTrue(keys.getValue().contains("auth:login:fail:ip:10.0.0.1"));
    }
}
