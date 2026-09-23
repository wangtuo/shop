package com.shop.marketing.inner;

import com.shop.api.marketing.dto.PriceCalcCommand;
import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.api.marketing.dto.PromotionConfirmCommand;
import com.shop.api.marketing.dto.PromotionLockCommand;
import com.shop.api.marketing.dto.PromotionReleaseCommand;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.marketing.engine.PriceEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-6：营销编排为「orderNo 分布式锁包裹事务」——
 * 业务事务（MarketingTxOps 代理方法，返回即已提交）完成后才允许 unlock。
 */
@ExtendWith(MockitoExtension.class)
class MarketingAppServiceTest {

    @Mock private PriceEngine priceEngine;
    @Mock private MarketingTxOps txOps;
    @Mock private ObjectProvider<RedissonClient> redissonProvider;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock rLock;

    private MarketingAppService appService;

    @BeforeEach
    void setUp() throws InterruptedException {
        appService = new MarketingAppService(priceEngine, txOps, redissonProvider);
        // 公共打桩用 lenient：calculate 不触锁、中断/抢锁失败用例不会走到全部桩
        lenient().when(redissonProvider.getIfAvailable()).thenReturn(redissonClient);
        lenient().when(redissonClient.getLock("mk:lock:promo:O1")).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        lenient().when(rLock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    @DisplayName("试算直接委托价格引擎，不加锁不开启事务")
    void calculate_委托引擎() {
        PriceCalcCommand cmd = PriceCalcCommand.builder().userId(1L).build();
        PriceCalcResult result = new PriceCalcResult();
        when(priceEngine.calculate(cmd)).thenReturn(result);
        assertEquals(result, appService.calculate(cmd));
        verify(redissonClient, never()).getLock(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("lock：先拿锁 → 事务内执行 → 提交完成后才 unlock（InOrder）")
    void lock_事务提交后才解锁() {
        appService.lock(PromotionLockCommand.builder().userId(1L).orderNo("O1").build());

        InOrder order = inOrder(redissonClient, txOps, rLock);
        order.verify(redissonClient).getLock("mk:lock:promo:O1");
        order.verify(txOps).lockInTx(org.mockito.ArgumentMatchers.any());
        order.verify(rLock).unlock();
    }

    @Test
    @DisplayName("confirm/release 同样在锁内执行，提交后解锁")
    void confirmAndRelease_锁内执行() {
        appService.confirm(PromotionConfirmCommand.builder().orderNo("O1").build());
        appService.release(PromotionReleaseCommand.builder().orderNo("O1").build());

        InOrder order = inOrder(txOps, rLock);
        order.verify(txOps).confirmInTx(org.mockito.ArgumentMatchers.any());
        order.verify(rLock).unlock();
        order.verify(txOps).releaseInTx(org.mockito.ArgumentMatchers.any());
        order.verify(rLock).unlock();
    }

    @Test
    @DisplayName("事务抛异常：异常外抛且锁仍在 finally 释放，且释放发生在事务方法返回之后")
    void lock_事务异常_仍在事务后释放锁() {
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.LIMIT_PURCHASE, "boom"))
                .when(txOps).lockInTx(org.mockito.ArgumentMatchers.any());

        assertThrows(BizException.class,
                () -> appService.lock(PromotionLockCommand.builder().orderNo("O1").build()));

        InOrder order = inOrder(txOps, rLock);
        order.verify(txOps).lockInTx(org.mockito.ArgumentMatchers.any());
        order.verify(rLock).unlock();
    }

    @Test
    @DisplayName("获取锁失败（等待超时）：不执行事务，抛频繁操作错误")
    void lock_加锁失败_不执行事务() throws InterruptedException {
        when(rLock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);

        assertThrows(BizException.class,
                () -> appService.lock(PromotionLockCommand.builder().orderNo("O1").build()));
        verify(txOps, never()).lockInTx(org.mockito.ArgumentMatchers.any());
        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("等锁被中断：恢复中断标志并抛系统错误")
    void lock_中断_抛错() throws InterruptedException {
        when(rLock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new InterruptedException("interrupted"));

        assertThrows(BizException.class,
                () -> appService.lock(PromotionLockCommand.builder().orderNo("O1").build()));
        assertTrue(Thread.currentThread().isInterrupted(), "中断标志必须恢复");
        // 清掉标志避免污染同线程后续用例
        Thread.interrupted();
    }

    @Test
    @DisplayName("RedissonClient 缺失：warn 降级无锁执行事务（不阻塞下单链路）")
    void lock_无Redis_降级执行() {
        when(redissonProvider.getIfAvailable()).thenReturn(null);

        appService.lock(PromotionLockCommand.builder().userId(1L).orderNo("O1").build());

        verify(txOps).lockInTx(org.mockito.ArgumentMatchers.any());
        verify(redissonClient, never()).getLock(org.mockito.ArgumentMatchers.anyString());
    }
}
