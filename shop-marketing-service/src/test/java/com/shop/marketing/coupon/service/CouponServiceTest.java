package com.shop.marketing.coupon.service;

import com.shop.api.marketing.enums.CouponIssueWays;
import com.shop.common.exception.BizException;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.entity.UserCoupon;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.coupon.mapper.UserCouponMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 券生命周期：五种发放方式、每人限领、库存条件扣减、重复领取回滚、过期扫描。 */
@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock private CouponMapper couponMapper;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private ObjectProvider<RedissonClient> redissonProvider;

    private CouponService service;

    @BeforeEach
    void setUp() {
        service = new CouponService(couponMapper, userCouponMapper, redissonProvider);
    }

    private Coupon coupon(int perUserLimit, int issueWay) {
        Coupon c = new Coupon();
        c.setId(50L);
        c.setStatus(1);
        c.setType(1);
        c.setScopeType(1);
        c.setFaceValueFen(2000L);
        c.setThresholdFen(10000L);
        c.setTotalCount(100);
        c.setPerUserLimit(perUserLimit);
        c.setIssueWay(issueWay);
        c.setValidType(2);
        c.setValidDays(7);
        c.setReceiveStartTime(LocalDateTime.now().minusHours(1));
        c.setReceiveEndTime(LocalDateTime.now().plusDays(1));
        return c;
    }

    @Test
    @DisplayName("主动领取成功：库存扣减 + 用户券状态未使用 + 有效期=领取后N天")
    void claim_正常领取_成功落库() {
        when(couponMapper.selectById(50L)).thenReturn(coupon(1, 1));
        when(userCouponMapper.selectCount(any())).thenReturn(0L);
        when(couponMapper.increaseReceived(50L)).thenReturn(1);

        Long id = service.claim(1L, 50L);

        ArgumentCaptor<UserCoupon> captor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).insert(captor.capture());
        UserCoupon uc = captor.getValue();
        assertEquals(1L, uc.getUserId());
        assertEquals(50L, uc.getCouponId());
        assertEquals(0, uc.getStatus());
        assertEquals(CouponIssueWays.ACTIVE_CLAIM.getCode(), uc.getIssueWay());
        assertTrue(uc.getValidEndTime().isAfter(LocalDateTime.now().plusDays(6)));
        // P2-1：无 requestNo 时 requestNo 为 claim-{userId}-{couponId}-{第N张}，第 1 张
        assertEquals("claim-1-50-1", uc.getRequestNo());
    }

    @Test
    @DisplayName("超出每人限领：拒绝且不扣库存")
    void claim_超出限领_拒绝() {
        when(couponMapper.selectById(50L)).thenReturn(coupon(1, 1));
        // claim 算号一次 + issue 限领校验一次，均返回已持有 1 张
        when(userCouponMapper.selectCount(any())).thenReturn(1L, 1L);

        assertThrows(BizException.class, () -> service.claim(1L, 50L));
        verify(couponMapper, never()).increaseReceived(anyLong());
    }

    @Test
    @DisplayName("券已领完：条件更新 0 行快速失败")
    void claim_库存不足_失败() {
        when(couponMapper.selectById(50L)).thenReturn(coupon(1, 1));
        when(userCouponMapper.selectCount(any())).thenReturn(0L, 0L);
        when(couponMapper.increaseReceived(50L)).thenReturn(0);

        assertThrows(BizException.class, () -> service.claim(1L, 50L));
        verify(userCouponMapper, never()).insert(any());
    }

    @Test
    @DisplayName("不在领取时间窗：拒绝")
    void claim_不在领取窗_拒绝() {
        Coupon c = coupon(1, 1);
        c.setReceiveEndTime(LocalDateTime.now().minusMinutes(1));
        when(couponMapper.selectById(50L)).thenReturn(c);
        assertThrows(BizException.class, () -> service.claim(1L, 50L));
    }

    @Test
    @DisplayName("新人礼包每人仅 1 次")
    void issue_新人礼包_第二次拒绝() {
        when(couponMapper.selectById(50L)).thenReturn(coupon(1, 3));
        when(userCouponMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BizException.class,
                () -> service.issue(1L, 50L, CouponIssueWays.NEW_USER.getCode(), "newuser-1"));
    }

    @Test
    @DisplayName("系统补偿/积分兑换不受每人限领约束")
    void issue_系统补偿_不受限领约束() {
        when(couponMapper.selectById(50L)).thenReturn(coupon(1, 4));
        when(couponMapper.increaseReceived(50L)).thenReturn(1);

        service.issue(1L, 50L, CouponIssueWays.COMPENSATE.getCode(), "cs-ticket-001");

        verify(userCouponMapper).insert(any(UserCoupon.class));
    }

    @Test
    @DisplayName("唯一键冲突（重复发券流水）：回滚库存计数并抛重复提交")
    void issue_唯一键冲突_回滚计数() {
        when(couponMapper.selectById(50L)).thenReturn(coupon(0, 5));
        when(couponMapper.increaseReceived(50L)).thenReturn(1);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk"))
                .when(userCouponMapper).insert(any(UserCoupon.class));

        assertThrows(BizException.class,
                () -> service.issue(1L, 50L, CouponIssueWays.POINTS_EXCHANGE.getCode(), "pts-1"));
        verify(couponMapper).decreaseReceived(50L);
    }

    @Test
    @DisplayName("预核销：未使用券条件更新，失败抛券不可用")
    void lockCoupons_任一失败整体异常() {
        when(userCouponMapper.lockCoupon(1L, 1L, "O1")).thenReturn(1);
        when(userCouponMapper.lockCoupon(2L, 1L, "O1")).thenReturn(0);
        assertThrows(BizException.class,
                () -> service.lockCoupons(1L, "O1", java.util.List.of(1L, 2L)));
    }

    @Test
    @DisplayName("释放：锁定券退回未使用；核销：转已使用（幂等条件更新）")
    void releaseAndConfirm_条件更新() {
        service.releaseCoupons("O1", java.util.List.of(1L));
        verify(userCouponMapper).releaseCoupon(1L, "O1");
        service.confirmCoupons("O1", java.util.List.of(1L));
        verify(userCouponMapper).useCoupon(1L, "O1");
    }

    @Test
    @DisplayName("过期扫描返回作废张数")
    void expireScanned_返回数量() {
        when(userCouponMapper.expireUnused(any())).thenReturn(7);
        assertEquals(7, service.expireScanned());
    }

    // ------------------------------------------------------------------
    // P2-1：领券幂等键改造
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private RBucket<String> mockRedisBucket(RedissonClient client, ArgumentCaptor<String> keyCaptor,
                                            ArgumentCaptor<Duration> ttlCaptor) {
        RBucket<String> bucket = mock(RBucket.class);
        when(redissonProvider.getIfAvailable()).thenReturn(client);
        org.mockito.Mockito.doReturn(bucket).when(client).getBucket(keyCaptor.capture());
        when(bucket.setIfAbsent(anyString(), ttlCaptor.capture())).thenReturn(true);
        return bucket;
    }

    @Test
    @DisplayName("P2-1 perUserLimit=2 可连领两张：requestNo 按第 N 张递增，防连点 3s 短 TTL")
    void claim_多张券_requestNo递增且短TTL防连点() {
        RedissonClient client = mock(RedissonClient.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        mockRedisBucket(client, keyCaptor, ttlCaptor);

        when(couponMapper.selectById(50L)).thenReturn(coupon(2, 1));
        // 每次 claim 先 countHeld 算第 N 张，issue 内限领校验再查一次：
        // claim1: 0(算号) 0(限领)；claim2: 1(算号→第2张) 1(限领，1<2 放行)
        when(userCouponMapper.selectCount(any())).thenReturn(0L, 0L, 1L, 1L);
        when(couponMapper.increaseReceived(50L)).thenReturn(1);

        service.claim(1L, 50L);
        service.claim(1L, 50L);

        ArgumentCaptor<UserCoupon> captor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertEquals(List.of("claim-1-50-1", "claim-1-50-2"),
                captor.getAllValues().stream().map(UserCoupon::getRequestNo).toList());
        assertTrue(keyCaptor.getValue().startsWith("marketing:coupon:debounce:1:50"));
        ttlCaptor.getAllValues().forEach(ttl -> assertEquals(Duration.ofSeconds(3), ttl));
    }

    @Test
    @DisplayName("P2-1 客户端 requestNo：作为 DB 幂等流水，Redis 请求级幂等键 24h")
    void claim_客户端requestNo_请求级幂等() {
        RedissonClient client = mock(RedissonClient.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        mockRedisBucket(client, keyCaptor, ttlCaptor);

        when(couponMapper.selectById(50L)).thenReturn(coupon(1, 1));
        when(userCouponMapper.selectCount(any())).thenReturn(0L);
        when(couponMapper.increaseReceived(50L)).thenReturn(1);

        service.claim(1L, 50L, "  REQ-001  ");

        ArgumentCaptor<UserCoupon> captor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).insert(captor.capture());
        assertEquals("REQ-001", captor.getValue().getRequestNo(), "requestNo 需 strip 后原样落库");
        assertTrue(keyCaptor.getValue().contains("1:50:REQ-001"));
        assertEquals(Duration.ofHours(24), ttlCaptor.getValue());
    }

    @Test
    @DisplayName("P2-1 窗口内重复点击：拒绝且不查券不扣库存")
    void claim_防连点_拒绝() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redissonProvider.getIfAvailable()).thenReturn(client);
        org.mockito.Mockito.doReturn(bucket).when(client).getBucket(anyString());
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(false);

        assertThrows(BizException.class, () -> service.claim(1L, 50L));
        verify(couponMapper, never()).selectById(anyLong());
        verify(couponMapper, never()).increaseReceived(anyLong());
    }

    @Test
    @DisplayName("P2-1 业务失败：删除幂等/防连点标记，允许立即重试")
    void claim_业务失败_删除标记放行重试() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redissonProvider.getIfAvailable()).thenReturn(client);
        org.mockito.Mockito.doReturn(bucket).when(client).getBucket(anyString());
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        when(couponMapper.selectById(50L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.claim(1L, 50L, "REQ-002"));
        verify(bucket).delete();
    }
}
