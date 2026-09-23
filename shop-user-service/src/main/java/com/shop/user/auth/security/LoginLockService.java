package com.shop.user.auth.security;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录防刷（M-2）：按用户名与客户端 IP 双维度统计连续登录失败次数。
 *
 * <ul>
 *   <li>计数 key：{@code auth:login:fail:user:{account}}、{@code auth:login:fail:ip:{ip}}；</li>
 *   <li>任一维度达到 {@link #MAX_FAILURES} 次即锁定 {@link #LOCK_MILLIS} 毫秒（15 分钟），
 *       计数 key 每次失败都续期 TTL（滑动窗口）；</li>
 *   <li>计数与续期通过单条 Lua 脚本原子完成，避免 INCR/EXPIRE 之间宕机导致永久锁定；</li>
 *   <li>登录成功删除两类 key 清零。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LoginLockService {

    public static final int MAX_FAILURES = 5;
    public static final long LOCK_MILLIS = 15L * 60 * 1000;

    private static final String USER_KEY_PREFIX = "auth:login:fail:user:";
    private static final String IP_KEY_PREFIX = "auth:login:fail:ip:";

    /**
     * 返回 1 表示已锁定：任一 key 计数 >= ARGV[1]。
     * KEYS[2] 为空串时跳过 IP 维度。
     */
    private static final DefaultRedisScript<Long> CHECK_SCRIPT = new DefaultRedisScript<>(
            "local function hit(k) "
                    + "if k ~= '' then local v=redis.call('GET',k); "
                    + "if v and tonumber(v)>=tonumber(ARGV[1]) then return 1 end end; "
                    + "return 0 end "
                    + "if hit(KEYS[1])==1 then return 1 end "
                    + "if #KEYS>1 and hit(KEYS[2])==1 then return 1 end "
                    + "return 0",
            Long.class);

    /**
     * 两个维度各 INCR 一次并刷新 PEXPIRE（滑动窗口）；KEYS[2] 为空串时跳过 IP 维度。
     * ARGV[1] = 锁定时长毫秒。
     */
    private static final DefaultRedisScript<Long> FAIL_SCRIPT = new DefaultRedisScript<>(
            "local cu=redis.call('INCR',KEYS[1]); redis.call('PEXPIRE',KEYS[1],ARGV[1]); "
                    + "if #KEYS>1 and KEYS[2]~='' then redis.call('INCR',KEYS[2]); "
                    + "redis.call('PEXPIRE',KEYS[2],ARGV[1]) end; "
                    + "return cu",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /** 锁定中直接抛业务异常；未锁定放行。 */
    public void assertNotLocked(String account, String clientIp) {
        Long locked = stringRedisTemplate.execute(CHECK_SCRIPT, buildKeys(account, clientIp),
                String.valueOf(MAX_FAILURES));
        if (locked != null && locked == 1L) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "账号或IP登录失败次数过多，已临时锁定15分钟，请稍后再试");
        }
    }

    /** 记录一次失败：双维度计数 + 滑动续期。 */
    public void recordFailure(String account, String clientIp) {
        stringRedisTemplate.execute(FAIL_SCRIPT, buildKeys(account, clientIp),
                String.valueOf(LOCK_MILLIS));
    }

    /** 登录成功后清零双维度计数。 */
    public void clearLock(String account, String clientIp) {
        stringRedisTemplate.delete(buildKeys(account, clientIp));
    }

    private List<String> buildKeys(String account, String clientIp) {
        List<String> keys = new ArrayList<>(2);
        keys.add(USER_KEY_PREFIX + (account == null ? "" : account));
        if (clientIp != null && !clientIp.isBlank()) {
            keys.add(IP_KEY_PREFIX + clientIp);
        }
        return keys;
    }
}
