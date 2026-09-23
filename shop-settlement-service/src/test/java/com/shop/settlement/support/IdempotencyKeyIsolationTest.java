package com.shop.settlement.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.framework.idempotent.IdempotentAspect;
import com.shop.settlement.deposit.dto.DepositPayRequest;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.withdraw.dto.ApplyWithdrawRequest;
import com.shop.settlement.withdraw.service.WithdrawService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M-3：幂等键必须含服务端身份 merchantId——两个商户用完全相同的账号+金额提现/缴费，
 * Redis 键也不同，互不互斥；clientToken 优先于兜底业务单号。
 */
class IdempotencyKeyIsolationTest {

    private RedissonClient redisson;
    private RBucket<Object> bucket;
    private IdempotentAspect aspect;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn((RBucket) bucket);
        when(bucket.setIfAbsent(eq(IdempotentAspect.INFLIGHT), any(Duration.class))).thenReturn(true);
        aspect = new IdempotentAspect(redisson, new ObjectMapper());
    }

    private Object invoke(Method method, Object[] args) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(pjp.getArgs()).thenReturn(args);
        return aspect.around(pjp);
    }

    @Test
    void 两个商户同账号同金额提现_幂等键不冲突() throws Throwable {
        Method method = WithdrawService.class.getMethod(
                "apply", long.class, ApplyWithdrawRequest.class, boolean.class);

        ApplyWithdrawRequest reqA = new ApplyWithdrawRequest();
        reqA.setAmountFen(100_000L);
        reqA.setChannel(1);
        reqA.setChannelAccount("6222020200001234");
        reqA.setWithdrawNo("WD2609160000000001");

        ApplyWithdrawRequest reqB = new ApplyWithdrawRequest();
        reqB.setAmountFen(100_000L);
        reqB.setChannel(1);
        reqB.setChannelAccount("6222020200001234");
        reqB.setWithdrawNo("WD2609160000000001"); // 同样的兜底单号也不能让两商户互斥

        invoke(method, new Object[]{1L, reqA, false});
        invoke(method, new Object[]{2L, reqB, false});

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisson, times(2)).getBucket(keyCaptor.capture());
        assertEquals("idem:settle:withdraw:1:WD2609160000000001", keyCaptor.getAllValues().get(0));
        assertEquals("idem:settle:withdraw:2:WD2609160000000001", keyCaptor.getAllValues().get(1));
    }

    @Test
    void 提现客户端令牌优先于兜底单号() throws Throwable {
        Method method = WithdrawService.class.getMethod(
                "apply", long.class, ApplyWithdrawRequest.class, boolean.class);
        ApplyWithdrawRequest req = new ApplyWithdrawRequest();
        req.setAmountFen(100_000L);
        req.setChannel(1);
        req.setChannelAccount("acct");
        req.setWithdrawNo("WD-FALLBACK");
        req.setClientToken("client-uuid-1");

        invoke(method, new Object[]{9L, req, false});

        verify(redisson).getBucket("idem:settle:withdraw:9:client-uuid-1");
    }

    @Test
    void 保证金缴费幂等键含商户号与兜底流水号() throws Throwable {
        Method method = DepositService.class.getMethod(
                "initiateDepositPay", long.class, DepositPayRequest.class, String.class);

        invoke(method, new Object[]{1L, payReq(500_000L, null), "DP2609160000000009"});
        invoke(method, new Object[]{2L, payReq(500_000L, null), "DP2609160000000009"});

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisson, times(2)).getBucket(keyCaptor.capture());
        assertEquals("idem:settle:deposit:1:DP2609160000000009", keyCaptor.getAllValues().get(0));
        assertEquals("idem:settle:deposit:2:DP2609160000000009", keyCaptor.getAllValues().get(1));
    }

    @Test
    void 保证金缴费客户端令牌优先() throws Throwable {
        Method method = DepositService.class.getMethod(
                "initiateDepositPay", long.class, DepositPayRequest.class, String.class);
        invoke(method, new Object[]{1L, payReq(500_000L, "deposit-token-7"), "DP-FALLBACK"});
        verify(redisson).getBucket("idem:settle:deposit:1:deposit-token-7");
    }

    private static DepositPayRequest payReq(long amountFen, String clientToken) {
        DepositPayRequest req = new DepositPayRequest();
        req.setAmountFen(amountFen);
        req.setPayMethod(1);
        req.setTerminal(1);
        req.setClientToken(clientToken);
        return req;
    }
}
