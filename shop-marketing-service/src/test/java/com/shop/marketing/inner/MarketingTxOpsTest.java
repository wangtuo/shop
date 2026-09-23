package com.shop.marketing.inner;

import com.shop.api.marketing.dto.CalcItem;
import com.shop.api.marketing.dto.PromotionConfirmCommand;
import com.shop.api.marketing.dto.PromotionLockCommand;
import com.shop.api.marketing.dto.PromotionReleaseCommand;
import com.shop.marketing.activity.service.GroupbuyService;
import com.shop.marketing.activity.service.PresaleService;
import com.shop.marketing.activity.service.SeckillService;
import com.shop.marketing.common.entity.MarketingLock;
import com.shop.marketing.common.mapper.MarketingLockMapper;
import com.shop.marketing.coupon.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 营销编排事务体（MarketingTxOps，P1-6 前的原 MarketingAppService 业务逻辑迁移至此）：
 * lock/confirm/release 按 orderNo 幂等与活动分派。锁/提交顺序见 MarketingAppServiceTest。
 */
@ExtendWith(MockitoExtension.class)
class MarketingTxOpsTest {

    @Mock private MarketingLockMapper lockMapper;
    @Mock private CouponService couponService;
    @Mock private SeckillService seckillService;
    @Mock private GroupbuyService groupbuyService;
    @Mock private PresaleService presaleService;

    private MarketingTxOps txOps;

    @BeforeEach
    void setUp() {
        txOps = new MarketingTxOps(lockMapper, couponService, seckillService, groupbuyService, presaleService);
    }

    private MarketingLock lock(String orderNo, int orderType, int status, String couponIds) {
        MarketingLock l = new MarketingLock();
        l.setOrderNo(orderNo);
        l.setUserId(1L);
        l.setOrderType(orderType);
        l.setActivityId(10L);
        l.setStatus(status);
        l.setUserCouponIds(couponIds);
        return l;
    }

    @Test
    @DisplayName("lock：普通单预核销券并落锁定记录")
    void lock_普通单_核销券落库() {
        when(lockMapper.selectOne(any())).thenReturn(null);
        PromotionLockCommand cmd = PromotionLockCommand.builder()
                .userId(1L).orderNo("O1").orderType(1)
                .userCouponIds(List.of(11L, 22L)).build();

        txOps.lockInTx(cmd);

        verify(couponService).lockCoupons(1L, "O1", List.of(11L, 22L));
        ArgumentCaptor<MarketingLock> captor = ArgumentCaptor.forClass(MarketingLock.class);
        verify(lockMapper).insert(captor.capture());
        assertEquals("11,22", captor.getValue().getUserCouponIds());
        assertEquals(0, captor.getValue().getStatus());
        verify(seckillService, never()).lock(any(), any(), any(), anyList());
    }

    @Test
    @DisplayName("lock：同 orderNo 重复请求直接成功，不重复核销/落库")
    void lock_重复_幂等返回() {
        when(lockMapper.selectOne(any())).thenReturn(lock("O1", 1, 0, ""));

        txOps.lockInTx(PromotionLockCommand.builder().userId(1L).orderNo("O1").build());

        verify(couponService, never()).lockCoupons(any(), any(), anyList());
        verify(lockMapper, never()).insert(any());
    }

    @Test
    @DisplayName("lock：秒杀单调秒杀资源锁定")
    void lock_秒杀单_分派秒杀() {
        when(lockMapper.selectOne(any())).thenReturn(null);
        List<CalcItem> items = List.of(CalcItem.builder().skuId(1L).qty(1).salePriceFen(100L).build());
        PromotionLockCommand cmd = PromotionLockCommand.builder()
                .userId(1L).orderNo("O2").orderType(2).activityId(10L).items(items).build();

        txOps.lockInTx(cmd);

        verify(seckillService).lock(1L, 10L, "O2", items);
        verify(groupbuyService, never()).openOrJoin(any(), any(), any());
    }

    @Test
    @DisplayName("lock：拼团单走开团/参团；预售单走定金登记")
    void lock_拼团与预售_分派() {
        when(lockMapper.selectOne(any())).thenReturn(null);
        txOps.lockInTx(PromotionLockCommand.builder()
                .userId(1L).orderNo("O3").orderType(3).activityId(11L).build());
        verify(groupbuyService).openOrJoin(1L, 11L, "O3");

        when(lockMapper.selectOne(any())).thenReturn(null);
        txOps.lockInTx(PromotionLockCommand.builder()
                .userId(1L).orderNo("O4").orderType(4).activityId(12L).build());
        verify(presaleService).register(1L, 12L, "O4");
    }

    @Test
    @DisplayName("confirm：锁定中 → 核销券 + 秒杀扣减 + 状态转已确认")
    void confirm_锁定中_核销() {
        when(lockMapper.selectOne(any())).thenReturn(lock("O2", 2, 0, "11"));

        txOps.confirmInTx(PromotionConfirmCommand.builder().userId(1L).orderNo("O2").build());

        verify(couponService).confirmCoupons("O2", List.of(11L));
        verify(seckillService).confirm("O2");
        verify(lockMapper).updateStatus("O2", 0, 1);
    }

    @Test
    @DisplayName("confirm：预售单只做尾款核销；拼团单无额外资源扣减")
    void confirm_预售与拼团() {
        when(lockMapper.selectOne(any())).thenReturn(lock("O4", 4, 0, ""));
        txOps.confirmInTx(PromotionConfirmCommand.builder().userId(1L).orderNo("O4").build());
        verify(presaleService).confirm("O4");
        verify(seckillService, never()).confirm(any());

        when(lockMapper.selectOne(any())).thenReturn(lock("O3", 3, 0, ""));
        txOps.confirmInTx(PromotionConfirmCommand.builder().userId(1L).orderNo("O3").build());
        verify(groupbuyService, never()).release(any());
    }

    @Test
    @DisplayName("confirm：无锁定记录或已推进 → 幂等成功，不重复核销")
    void confirm_无记录或已确认_幂等() {
        when(lockMapper.selectOne(any())).thenReturn(null);
        txOps.confirmInTx(PromotionConfirmCommand.builder().orderNo("OX").build());
        verify(couponService, never()).confirmCoupons(any(), anyList());

        when(lockMapper.selectOne(any())).thenReturn(lock("O1", 1, 1, ""));
        txOps.confirmInTx(PromotionConfirmCommand.builder().orderNo("O1").build());
        verify(lockMapper, never()).updateStatus(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("release：秒杀单退回券/库存并转已释放")
    void release_秒杀单_全量回退() {
        when(lockMapper.selectOne(any())).thenReturn(lock("O2", 2, 0, "11,22"));

        txOps.releaseInTx(PromotionReleaseCommand.builder().userId(1L).orderNo("O2").build());

        verify(couponService).releaseCoupons("O2", List.of(11L, 22L));
        verify(seckillService).release("O2");
        verify(lockMapper).updateStatus("O2", 0, 2);
    }

    @Test
    @DisplayName("release：拼团单成员退出；预售单不退定金（不触达预售服务）")
    void release_拼团退出_预售不退定金() {
        when(lockMapper.selectOne(any())).thenReturn(lock("O3", 3, 0, ""));
        txOps.releaseInTx(PromotionReleaseCommand.builder().orderNo("O3").build());
        verify(groupbuyService).release("O3");

        when(lockMapper.selectOne(any())).thenReturn(lock("O4", 4, 0, ""));
        txOps.releaseInTx(PromotionReleaseCommand.builder().orderNo("O4").build());
        verify(presaleService, never()).cancelByOrderNo(any());
    }

    @Test
    @DisplayName("release：无锁定记录按成功返回，取消链路可安全重试")
    void release_无记录_幂等成功() {
        when(lockMapper.selectOne(any())).thenReturn(null);
        txOps.releaseInTx(PromotionReleaseCommand.builder().orderNo("OX").build());
        verify(couponService, never()).releaseCoupons(any(), anyList());
        verify(lockMapper, never()).updateStatus(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
