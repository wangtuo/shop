package com.shop.framework.lock;

import com.shop.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P1-3：默认走看门狗（leaseTime=-1），异常/抢锁失败语义正确。 */
class DistributedLockTemplateTest {

    private RedissonClient client;
    private RLock lock;
    private DistributedLockTemplate template;

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(client.getLock("k")).thenReturn(lock);
        template = new DistributedLockTemplate(client);
    }

    @Test
    void 默认租约_使用看门狗_leaseTime为负一并在finally释放() throws Exception {
        when(lock.tryLock(3L, -1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        AtomicBoolean ran = new AtomicBoolean(false);
        template.execute("k", () -> ran.set(true));

        assertTrue(ran.get());
        verify(lock).tryLock(3L, -1L, TimeUnit.SECONDS);
        verify(lock).unlock();
    }

    @Test
    void 动作抛异常_锁仍释放() throws Exception {
        when(lock.tryLock(3L, -1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> template.execute("k", () -> { throw new IllegalStateException("boom"); }));
        verify(lock).unlock();
    }

    @Test
    void 抢锁失败_抛频繁请求且不执行业务() throws Exception {
        when(lock.tryLock(3L, -1L, TimeUnit.SECONDS)).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> template.execute("k", () -> { }));
        assertEquals(10007, ex.getCode());
        verify(lock, org.mockito.Mockito.never()).unlock();
    }
}
