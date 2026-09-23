package com.shop.marketing.activity.lottery.service;

import com.shop.api.marketing.enums.CouponIssueWays;
import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.PointsLockCommand;
import com.shop.api.user.dto.PointsRefundCommand;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.common.util.JsonUtils;
import com.shop.framework.id.IdGenerator;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.LotteryRecord;
import com.shop.marketing.activity.lottery.dto.DrawResult;
import com.shop.marketing.activity.lottery.dto.PrizeStockVO;
import com.shop.marketing.activity.lottery.entity.LotteryPrizeStock;
import com.shop.marketing.activity.lottery.mapper.LotteryPrizeStockMapper;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.LotteryRecordMapper;
import com.shop.marketing.activity.support.ActivityRule;
import com.shop.marketing.coupon.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 积分抽奖：积分两阶段与回退、日限、权重命中、库存 CAS 超发降级、三类发奖幂等号、失败补偿。
 */
@ExtendWith(MockitoExtension.class)
class LotteryServiceTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private LotteryRecordMapper recordMapper;
    @Mock private LotteryPrizeStockMapper prizeStockMapper;
    @Mock private UserClient userClient;
    @Mock private CouponService couponService;
    @Mock private IdGenerator idGenerator;
    @Mock private ObjectProvider<RedissonClient> redissonProvider;

    private LotteryService service;

    @BeforeEach
    void setUp() {
        service = new LotteryService(activityMapper, recordMapper, prizeStockMapper,
                userClient, couponService, idGenerator, redissonProvider);
        service.random = new Random(42);
        org.mockito.Mockito.lenient().when(redissonProvider.getIfAvailable()).thenReturn(null);
        org.mockito.Mockito.lenient().when(idGenerator.nextId()).thenReturn(123L);
    }

    private Activity activity(ActivityRule rule) {
        Activity a = new Activity();
        a.setId(11L);
        a.setType(14);
        a.setStatus(1);
        a.setStartTime(LocalDateTime.now().minusHours(1));
        a.setEndTime(LocalDateTime.now().plusHours(5));
        a.setRuleJson(JsonUtils.toJson(rule));
        return a;
    }

    private ActivityRule rule() {
        ActivityRule rule = new ActivityRule();
        rule.setLotteryCostPoints(100);
        rule.setLotteryDailyLimit(2);
        return rule;
    }

    private LotteryPrizeStock stock(long id, int type, int weight, int total, int issued) {
        LotteryPrizeStock s = new LotteryPrizeStock();
        s.setId(id);
        s.setActivityId(11L);
        s.setPrizeCode("P" + id);
        s.setPrizeName("奖品" + id);
        s.setPrizeType(type);
        s.setWeight(weight);
        s.setTotalStock(total);
        s.setIssuedCount(issued);
        if (type == 1) {
            s.setPoints(500);
        }
        if (type == 2) {
            s.setCouponId(77L);
        }
        return s;
    }

    private void stubPointsLockDeductOk() {
        when(userClient.lockPoints(any())).thenReturn(Result.success());
        when(userClient.deductPoints(any())).thenReturn(Result.success());
    }

    @Test
    @DisplayName("活动不存在/非进行中拒绝")
    void activityUnavailable() {
        when(activityMapper.selectById(11L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.draw(1L, 11L));
        assertEquals(ErrorCode.ACTIVITY_NOT_AVAILABLE.getCode(), ex.getCode());
        verify(userClient, never()).lockPoints(any());
    }

    @Test
    @DisplayName("日限：DB 当日计数达上限拒绝且不扣积分")
    void dailyLimitRejected() {
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(2L);
        BizException ex = assertThrows(BizException.class, () -> service.draw(1L, 11L));
        assertEquals(ErrorCode.LIMIT_PURCHASE.getCode(), ex.getCode());
        verify(userClient, never()).lockPoints(any());
        verify(recordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("积分不足：lock 失败拒绝且不写抽奖记录")
    void pointsNotEnough() {
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(userClient.lockPoints(any()))
                .thenReturn(Result.fail(ErrorCode.FORBIDDEN, "积分余额不足"));
        BizException ex = assertThrows(BizException.class, () -> service.draw(1L, 11L));
        assertEquals(ErrorCode.POINTS_NOT_ENOUGH.getCode(), ex.getCode());
        verify(userClient, never()).deductPoints(any());
        verify(recordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("deduct 失败：release 回退冻结积分，不写记录")
    void deductFailedReleasesLock() {
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(userClient.lockPoints(any())).thenReturn(Result.success());
        when(userClient.deductPoints(any()))
                .thenReturn(Result.fail(ErrorCode.SYSTEM_ERROR, "扣减失败"));
        when(userClient.releasePoints(any())).thenReturn(Result.success());
        assertThrows(BizException.class, () -> service.draw(1L, 11L));
        verify(userClient).releasePoints(any());
        verify(recordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("无奖品/权重为 0：谢谢参与，积分仍扣减且无任何补偿")
    void thanksNoPrize() {
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        stubPointsLockDeductOk();
        when(prizeStockMapper.selectList(any())).thenReturn(new ArrayList<>());

        DrawResult result = service.draw(1L, 11L);

        assertEquals(LotteryService.PRIZE_THANKS, result.getPrizeType());
        assertNull(result.getPrizeCode());
        assertEquals(100, result.getCostPoints());
        verify(userClient, never()).grantPoints(any());
        verify(userClient, never()).refundPoints(any());
        verify(recordMapper).insert(any(LotteryRecord.class));
    }

    @Test
    @DisplayName("零积分免费抽奖：不调积分链路，记录 costPoints=0")
    void freeDraw() {
        ActivityRule r = rule();
        r.setLotteryCostPoints(0);
        when(activityMapper.selectById(11L)).thenReturn(activity(r));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(prizeStockMapper.selectList(any())).thenReturn(new ArrayList<>());

        DrawResult result = service.draw(1L, 11L);

        assertEquals(0, result.getCostPoints());
        verify(userClient, never()).lockPoints(any());
        verify(userClient, never()).deductPoints(any());
    }

    @Test
    @DisplayName("积分奖品：grant 幂等号 LOTTERY-POINT:{recordId}，记录落库")
    void pointsPrizeGrant() {
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        stubPointsLockDeductOk();
        when(prizeStockMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(stock(1L, 1, 100, 0, 0))));
        when(userClient.grantPoints(any())).thenReturn(Result.success());

        DrawResult result = service.draw(1L, 11L);

        assertEquals(LotteryService.PRIZE_POINTS, result.getPrizeType());
        ArgumentCaptor<GrantPointsCommand> captor = ArgumentCaptor.forClass(GrantPointsCommand.class);
        verify(userClient).grantPoints(captor.capture());
        GrantPointsCommand cmd = captor.getValue();
        assertEquals("LOTTERY-POINT:123", cmd.getBizNo());
        assertEquals(500L, cmd.getPoints());
        assertEquals(1L, cmd.getUserId());
    }

    @Test
    @DisplayName("优惠券奖品：issue 幂等 requestNo=LOTTERY:{recordId} 且走活动发放方式")
    void couponPrizeIssue() {
        LotteryPrizeStock coupon = stock(2L, 2, 100, 0, 0);
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        stubPointsLockDeductOk();
        when(prizeStockMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(coupon)));

        DrawResult result = service.draw(1L, 11L);

        assertEquals(LotteryService.PRIZE_COUPON, result.getPrizeType());
        verify(couponService).issue(1L, 77L, CouponIssueWays.ACTIVITY.getCode(), "LOTTERY:123");
        // 不限量奖品不调库存 CAS
        verify(prizeStockMapper, never()).occupy(anyLong());
    }

    @Test
    @DisplayName("有量奖品：命中先条件 UPDATE 占库存，成功才发奖")
    void finiteStockOccupySuccess() {
        LotteryPrizeStock coupon = stock(2L, 2, 100, 10, 3);
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        stubPointsLockDeductOk();
        when(prizeStockMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(coupon)));
        when(prizeStockMapper.occupy(2L)).thenReturn(1);

        service.draw(1L, 11L);

        verify(prizeStockMapper).occupy(2L);
        verify(couponService).issue(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    @DisplayName("库存 CAS 0 行（最后一件被并发抽走）：降级谢谢参与，不超发，不发券")
    void finiteStockExhaustedDegrades() {
        LotteryPrizeStock coupon = stock(2L, 2, 100, 10, 10);
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        stubPointsLockDeductOk();
        when(prizeStockMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(coupon)));
        when(prizeStockMapper.occupy(2L)).thenReturn(0);

        DrawResult result = service.draw(1L, 11L);

        assertEquals(LotteryService.PRIZE_THANKS, result.getPrizeType());
        assertNull(result.getPrizeCode());
        verify(couponService, never()).issue(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(userClient, never()).refundPoints(any());
    }

    @Test
    @DisplayName("发券异常：回补库存 + refund 退还积分（LOTTERY-REFUND 幂等号），记录不写")
    void grantFailureCompensates() {
        LotteryPrizeStock coupon = stock(2L, 2, 100, 10, 0);
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        stubPointsLockDeductOk();
        when(prizeStockMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(coupon)));
        when(prizeStockMapper.occupy(2L)).thenReturn(1);
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.COUPON_LIMIT, "券已领完"))
                .when(couponService).issue(anyLong(), anyLong(),
                        org.mockito.ArgumentMatchers.anyInt(), any());
        when(userClient.refundPoints(any())).thenReturn(Result.success());

        assertThrows(BizException.class, () -> service.draw(1L, 11L));

        verify(prizeStockMapper).releaseOne(2L);
        ArgumentCaptor<PointsRefundCommand> captor = ArgumentCaptor.forClass(PointsRefundCommand.class);
        verify(userClient).refundPoints(captor.capture());
        assertEquals("LOTTERY-REFUND:11:123", captor.getValue().getBizNo());
        assertEquals(100L, captor.getValue().getPoints());
        verify(recordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("抽奖记录落库失败：补偿回补库存并退还积分")
    void recordInsertFailureCompensates() {
        LotteryPrizeStock coupon = stock(2L, 2, 100, 10, 0);
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        stubPointsLockDeductOk();
        when(prizeStockMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(coupon)));
        when(prizeStockMapper.occupy(2L)).thenReturn(1);
        when(recordMapper.insert(any(LotteryRecord.class)))
                .thenThrow(new RuntimeException("db down"));
        when(userClient.refundPoints(any())).thenReturn(Result.success());

        assertThrows(RuntimeException.class, () -> service.draw(1L, 11L));
        verify(prizeStockMapper).releaseOne(2L);
        verify(userClient).refundPoints(any());
    }

    @Test
    @DisplayName("积分 lock 命令携带 cost/抵现 0/消费场景，bizNo=LOTTERY:{activityId}:{recordId}")
    void lockCommandShape() {
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(userClient.lockPoints(any())).thenReturn(Result.success());
        when(userClient.deductPoints(any())).thenReturn(Result.success());
        when(prizeStockMapper.selectList(any())).thenReturn(new ArrayList<>());

        service.draw(1L, 11L);

        ArgumentCaptor<PointsLockCommand> captor = ArgumentCaptor.forClass(PointsLockCommand.class);
        verify(userClient).lockPoints(captor.capture());
        PointsLockCommand cmd = captor.getValue();
        assertEquals("LOTTERY:11:123", cmd.getBizNo());
        assertEquals(100L, cmd.getPoints());
        assertEquals(0L, cmd.getDeductFen());
    }

    @Test
    @DisplayName("权重分布：100/200/700 大样本命中比例落在期望区间")
    void weightedDistribution() {
        List<LotteryPrizeStock> prizes = List.of(
                stock(1L, 3, 100, 0, 0),
                stock(2L, 3, 200, 0, 0),
                stock(3L, 3, 700, 0, 0));
        int[] hits = new int[3];
        Random rnd = new Random(2026);
        int n = 3000;
        for (int i = 0; i < n; i++) {
            hits[LotteryService.weightedIndex(new ArrayList<>(prizes), 1000, rnd)]++;
        }
        double tol = 0.06 * n;
        assertTrue(Math.abs(hits[0] - 0.10 * n) <= tol, "10% bucket=" + hits[0]);
        assertTrue(Math.abs(hits[1] - 0.20 * n) <= tol, "20% bucket=" + hits[1]);
        assertTrue(Math.abs(hits[2] - 0.70 * n) <= tol, "70% bucket=" + hits[2]);
    }

    @Test
    @DisplayName("奖品视图：有量奖品返剩余；谢谢参与/不限量余量为 null")
    void prizesView() {
        LotteryPrizeStock finite = stock(1L, 2, 100, 10, 4);
        LotteryPrizeStock unlimited = stock(2L, 1, 100, 0, 0);
        LotteryPrizeStock thanks = stock(3L, 3, 50, 10, 9);
        when(prizeStockMapper.selectList(any()))
                .thenReturn(List.of(finite, unlimited, thanks));

        List<PrizeStockVO> vos = service.prizes(11L);

        assertEquals(3, vos.size());
        assertEquals(6, vos.get(0).getRemain());
        assertNull(vos.get(1).getRemain());
        assertNull(vos.get(2).getRemain());
    }
}
