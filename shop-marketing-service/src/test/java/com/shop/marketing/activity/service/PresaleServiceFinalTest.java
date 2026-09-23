package com.shop.marketing.activity.service;

import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.marketing.activity.entity.PresaleOrder;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.PresaleOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B5 营销侧：尾款单/定金单关联反查兜底、尾款窗口校验、取消链路“定金不退” no-op 语义
 * （营销不调 PayClient、不发任何退款事件；释放对预售 no-op）。
 */
@ExtendWith(MockitoExtension.class)
class PresaleServiceFinalTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private PresaleOrderMapper presaleOrderMapper;
    @Mock private OutboxPublisher outboxPublisher;

    private PresaleService service;

    @BeforeEach
    void setUp() {
        service = new PresaleService(activityMapper, presaleOrderMapper, outboxPublisher);
    }

    private PresaleOrder deposit(int status, LocalDateTime start, LocalDateTime end) {
        PresaleOrder o = new PresaleOrder();
        o.setId(1L);
        o.setActivityId(12L);
        o.setUserId(9L);
        o.setOrderNo("DEP1");
        o.setDepositFen(5000L);
        o.setInflateDeductFen(10000L);
        o.setFinalPayFen(80000L);
        o.setStatus(status);
        o.setFinalStartTime(start);
        o.setFinalEndTime(end);
        return o;
    }

    @Test
    @DisplayName("parentOrderNo 直查命中：返回对应定金单")
    void resolveByParentOrderNo() {
        PresaleOrder d = deposit(0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(2));
        when(presaleOrderMapper.selectOne(any())).thenReturn(d);
        assertSame(d, service.resolveDepositOrder(12L, 9L, "DEP1"));
    }

    @Test
    @DisplayName("parentOrderNo 直查未命中：硬失败不猜测")
    void resolveByParentOrderNoMissing() {
        when(presaleOrderMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.resolveDepositOrder(12L, 9L, "DEPX"));
    }

    @Test
    @DisplayName("parentOrderNo 缺失反查：activityId+userId+status=0 唯一记录兜底关联")
    void resolveByReverseLookupUnique() {
        PresaleOrder d = deposit(0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(2));
        when(presaleOrderMapper.selectList(any())).thenReturn(List.of(d));
        assertSame(d, service.resolveDepositOrder(12L, 9L, null));
    }

    @Test
    @DisplayName("反查 0 条：报活动不可用；多条：CONFLICT 要求显式 parentOrderNo")
    void resolveByReverseLookupZeroOrMany() {
        when(presaleOrderMapper.selectList(any())).thenReturn(List.of());
        assertThrows(BizException.class, () -> service.resolveDepositOrder(12L, 9L, null));

        PresaleOrder a = deposit(0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(2));
        PresaleOrder b = deposit(0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(2));
        when(presaleOrderMapper.selectList(any())).thenReturn(List.of(a, b));
        assertThrows(BizException.class, () -> service.resolveDepositOrder(12L, 9L, ""));
    }

    @Test
    @DisplayName("尾款窗口校验：窗内通过；status 非 0/窗外硬失败")
    void validateFinalStageWindow() {
        service.validateFinalStage(
                deposit(0, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(2)));
        assertThrows(BizException.class, () -> service.validateFinalStage(
                deposit(1, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(2))));
        assertThrows(BizException.class, () -> service.validateFinalStage(
                deposit(0, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusDays(2))));
        assertThrows(BizException.class, () -> service.validateFinalStage(
                deposit(0, LocalDateTime.now().minusDays(4), LocalDateTime.now().minusMinutes(1))));
    }

    @Test
    @DisplayName("尾款超时取消：仅预售单置取消 + 发 PRESALE CANCEL，定金不退（无任何退款调用/事件）")
    void cancelKeepsDeposit() {
        PresaleOrder overdue = deposit(0,
                LocalDateTime.now().minusDays(4), LocalDateTime.now().minusMinutes(1));
        when(presaleOrderMapper.selectOne(any())).thenReturn(overdue);
        when(presaleOrderMapper.markTimeoutByOrderNo(eq("DEP1"), any())).thenReturn(1);

        service.cancelByOrderNo("DEP1");

        verify(presaleOrderMapper).markTimeoutByOrderNo(eq("DEP1"), any());
        // 只允许发预售域 CANCEL 事件；营销域没有也不应有退款类事件（定金不退，退款由订单/支付域编排）
        verify(outboxPublisher).publish(eq(MqTopics.PRESALE_EVENT), eq("3"), any(), eq("DEP1"));
        verify(outboxPublisher, never()).publishDelay(any(), any(), any(), any(), anyLong());
    }
}
