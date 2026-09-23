package com.shop.marketing.activity.service;

import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.PresaleOrder;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.PresaleOrderMapper;
import com.shop.framework.outbox.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 预售：定金登记+膨胀、尾款 3 天窗口、超时取消定金不退、延时消息双保险。 */
@ExtendWith(MockitoExtension.class)
class PresaleServiceTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private PresaleOrderMapper presaleOrderMapper;
    @Mock private OutboxPublisher outboxPublisher;

    private PresaleService service;

    @BeforeEach
    void setUp() {
        service = new PresaleService(activityMapper, presaleOrderMapper, outboxPublisher);
    }

    private Activity activity() {
        Activity a = new Activity();
        a.setId(12L);
        a.setType(12);
        a.setStatus(1);
        a.setStartTime(LocalDateTime.now().minusHours(24));
        a.setEndTime(LocalDateTime.now().plusHours(24));
        a.setRuleJson("{\"depositFen\":5000,\"inflateDeductFen\":10000,\"finalPayFen\":80000,\"finalPayDays\":3}");
        return a;
    }

    @Test
    @DisplayName("定金支付：登记预售单（尾款窗口 3 天）+ 发定金事件 + 投超时取消延时消息")
    void register_定金支付_登记并发延时双保险() {
        when(presaleOrderMapper.selectOne(any())).thenReturn(null);
        when(activityMapper.selectById(12L)).thenReturn(activity());

        service.register(1L, 12L, "O1");

        ArgumentCaptor<PresaleOrder> captor = ArgumentCaptor.forClass(PresaleOrder.class);
        verify(presaleOrderMapper).insert(captor.capture());
        PresaleOrder saved = captor.getValue();
        assertEquals(5000, saved.getDepositFen());
        assertEquals(10000, saved.getInflateDeductFen());
        assertEquals(0, saved.getStatus());
        assertTrue(saved.getFinalEndTime().isAfter(saved.getFinalStartTime().plusDays(2)));
        verify(outboxPublisher).publish(eq("shop_presale_event"), eq("1"), any(), eq("O1"));
        // R4-25：尾款超时【延时行】键必须带 #final，与到期后即时取消事件的裸 orderNo 键隔离
        verify(outboxPublisher).publishDelay(eq("shop_presale_event"), eq("3"), any(), eq("O1#final"), anyLong());
    }

    @Test
    @DisplayName("重复定金登记（同 orderNo）幂等返回")
    void register_重复订单_幂等() {
        when(presaleOrderMapper.selectOne(any())).thenReturn(new PresaleOrder());
        service.register(1L, 12L, "O1");
        verify(presaleOrderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("尾款支付：定金已付 → 尾款已付")
    void confirm_尾款核销() {
        service.confirm("O1");
        verify(presaleOrderMapper).markFinalPaid("O1");
    }

    @Test
    @DisplayName("尾款超时扫描：窗口外未付订单取消并发 CANCEL 事件（定金不退）")
    void timeoutScan_超期_取消并发事件() {
        PresaleOrder o = new PresaleOrder();
        o.setId(1L);
        o.setActivityId(12L);
        o.setUserId(1L);
        o.setOrderNo("O1");
        o.setFinalEndTime(LocalDateTime.now().minusMinutes(1));
        o.setStatus(0);
        when(presaleOrderMapper.selectList(any())).thenReturn(List.of(o));
        when(presaleOrderMapper.markTimeoutByOrderNo(eq("O1"), any())).thenReturn(1);

        int n = service.timeoutScan();

        assertEquals(1, n);
        verify(outboxPublisher).publish(eq("shop_presale_event"), eq("3"), any(), eq("O1"));
    }

    @Test
    @DisplayName("延时消息提前到达（尾款窗口未结束）：不取消")
    void cancelByOrderNo_未到截止_不处理() {
        PresaleOrder o = new PresaleOrder();
        o.setOrderNo("O1");
        o.setStatus(0);
        o.setFinalEndTime(LocalDateTime.now().plusDays(1));
        when(presaleOrderMapper.selectOne(any())).thenReturn(o);

        service.cancelByOrderNo("O1");

        verify(presaleOrderMapper, never()).markTimeoutByOrderNo(any(), any());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("延时消息到达且已超期：单条条件更新取消，幂等")
    void cancelByOrderNo_已超期_取消() {
        PresaleOrder o = new PresaleOrder();
        o.setActivityId(12L);
        o.setUserId(1L);
        o.setOrderNo("O1");
        o.setStatus(0);
        o.setFinalEndTime(LocalDateTime.now().minusMinutes(1));
        when(presaleOrderMapper.selectOne(any())).thenReturn(o);
        when(presaleOrderMapper.markTimeoutByOrderNo(eq("O1"), any())).thenReturn(1);

        service.cancelByOrderNo("O1");

        verify(outboxPublisher).publish(eq("shop_presale_event"), eq("3"), any(), eq("O1"));
    }
}
