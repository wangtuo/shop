package com.shop.marketing.inner;

import com.shop.api.marketing.dto.PromotionLockCommand;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.marketing.activity.service.GroupbuyService;
import com.shop.marketing.activity.service.PresaleService;
import com.shop.marketing.activity.service.SeckillService;
import com.shop.marketing.common.mapper.MarketingLockMapper;
import com.shop.marketing.coupon.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4-4/B4：建单同步道空活动硬失败——orderType=2/3/4 缺活动 ID 必须抛 PARAM_INVALID，
 * 禁止静默按普通价/普通资源下单，且不核销券、不写 t_marketing_lock。
 */
@ExtendWith(MockitoExtension.class)
class MarketingTxOpsActivityRequiredTest {

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

    @Test
    @DisplayName("orderType=2 秒杀缺 activityId：硬失败 PARAM_INVALID，不锁券/不锁秒杀/不落锁定记录")
    void lock_秒杀缺活动ID_硬失败() {
        when(lockMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> txOps.lockInTx(
                PromotionLockCommand.builder().userId(1L).orderNo("O2").orderType(2).build()));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(seckillService, never()).lock(any(), any(), any(), anyList());
        verify(couponService, never()).lockCoupons(any(), any(), anyList());
        verify(lockMapper, never()).insert(any());
    }

    @Test
    @DisplayName("orderType=3 拼团缺 activityId：硬失败且不开团")
    void lock_拼团缺活动ID_硬失败() {
        when(lockMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> txOps.lockInTx(
                PromotionLockCommand.builder().userId(1L).orderNo("O3").orderType(3).build()));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(groupbuyService, never()).openOrJoin(any(), any(), any());
        verify(lockMapper, never()).insert(any());
    }

    @Test
    @DisplayName("orderType=4 预售缺 activityId：硬失败且不登记定金")
    void lock_预售缺活动ID_硬失败() {
        when(lockMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> txOps.lockInTx(
                PromotionLockCommand.builder().userId(1L).orderNo("O4").orderType(4).build()));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(presaleService, never()).register(any(), any(), any());
        verify(lockMapper, never()).insert(any());
    }

    @Test
    @DisplayName("普通单(orderType=1)无活动 ID 不受影响，正常锁券落库")
    void lock_普通单_不受硬失败影响() {
        when(lockMapper.selectOne(any())).thenReturn(null);
        txOps.lockInTx(PromotionLockCommand.builder().userId(1L).orderNo("O1").orderType(1).build());
        verify(couponService).lockCoupons(any(), any(), anyList());
        verify(lockMapper).insert(any());
        verify(seckillService, never()).lock(any(), any(), any(), anyList());
    }

    @Test
    @DisplayName("带活动 ID 的秒杀单正常分派（硬失败校验不误伤正常活动单）")
    void lock_秒杀带活动ID_正常分派() {
        when(lockMapper.selectOne(any())).thenReturn(null);
        txOps.lockInTx(PromotionLockCommand.builder()
                .userId(1L).orderNo("O2").orderType(2).activityId(10L)
                .items(java.util.List.of()).build());
        verify(seckillService).lock(any(), org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("O2"), anyList());
    }
}
