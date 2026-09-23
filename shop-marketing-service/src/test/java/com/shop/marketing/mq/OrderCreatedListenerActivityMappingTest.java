package com.shop.marketing.mq;

import com.shop.api.marketing.dto.PromotionLockCommand;
import com.shop.api.order.event.OrderCreatedEvent;
import com.shop.api.order.event.OrderItemMessage;
import com.shop.marketing.inner.MarketingAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * W4-4/B4：ORDER_CREATED 第二道锁按活动类型显式映射 ID——
 * 2 只认 seckillActivityId、3 只认 groupbuyActivityId、4 只认 presaleActivityId；
 * 活动单缺对应 ID 跳过本类型锁且绝不降级为普通单。
 */
@ExtendWith(MockitoExtension.class)
class OrderCreatedListenerActivityMappingTest {

    @Mock private MqConsumeTemplate consumeTemplate;
    @Mock private MarketingAppService marketingAppService;

    private OrderCreatedListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrderCreatedListener(consumeTemplate, marketingAppService);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(3)).run();
            return null;
        }).when(consumeTemplate).runOnce(anyString(), anyString(), anyString(), any(Runnable.class));
    }

    private OrderItemMessage item(Long seckillId, Long groupbuyId, Long presaleId, String groupNo) {
        OrderItemMessage m = new OrderItemMessage();
        m.setSkuId(1L);
        m.setQty(1);
        m.setSalePriceFen(100L);
        m.setSeckillActivityId(seckillId);
        m.setGroupbuyActivityId(groupbuyId);
        m.setPresaleActivityId(presaleId);
        m.setGroupNo(groupNo);
        return m;
    }

    private OrderCreatedEvent event(int orderType, OrderItemMessage... items) {
        OrderCreatedEvent e = new OrderCreatedEvent();
        e.setUserId(7L);
        e.setOrderNo("O1");
        e.setOrderType(orderType);
        e.setItems(List.of(items));
        return e;
    }

    private PromotionLockCommand captured() {
        ArgumentCaptor<PromotionLockCommand> c = ArgumentCaptor.forClass(PromotionLockCommand.class);
        verify(marketingAppService).lock(c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("秒杀单带 seckillActivityId：orderType=2 + 秒杀 ID 入锁")
    void 秒杀单_映射秒杀ID() {
        listener.onMessage(event(2, item(10L, null, null, null)));
        PromotionLockCommand cmd = captured();
        assertEquals(2, cmd.getOrderType());
        assertEquals(10L, cmd.getActivityId());
    }

    @Test
    @DisplayName("拼团单带 groupbuyActivityId/groupNo：orderType=3 显式映射，不错认为秒杀")
    void 拼团单_映射拼团ID与团号() {
        listener.onMessage(event(3, item(null, 11L, null, "G1")));
        PromotionLockCommand cmd = captured();
        assertEquals(3, cmd.getOrderType());
        assertEquals(11L, cmd.getActivityId());
        assertEquals(11L, cmd.getGroupbuyActivityId());
        assertEquals("G1", cmd.getGroupNo());
    }

    @Test
    @DisplayName("预售单带 presaleActivityId：orderType=4 显式映射")
    void 预售单_映射预售ID() {
        listener.onMessage(event(4, item(null, null, 12L, null)));
        assertEquals(12L, captured().getActivityId());
    }

    @Test
    @DisplayName("秒杀单缺 seckillActivityId（即使带其他类型 ID）：跳过本类型锁，不降级普通单锁库存")
    void 秒杀单缺对应ID_跳过且不当普通单() {
        listener.onMessage(event(2, item(null, 11L, null, null)));
        verify(marketingAppService, never()).lock(any());
    }

    @Test
    @DisplayName("拼团/预售缺对应 ID：同样跳过，不降级普通单")
    void 拼团预售缺ID_跳过() {
        listener.onMessage(event(3, item(10L, null, null, null)));
        listener.onMessage(event(4, item(10L, null, null, null)));
        verify(marketingAppService, never()).lock(any());
    }

    @Test
    @DisplayName("普通单(orderType=1)：activityId 为 null 正常锁券/普通资源，不受活动 ID 闸门影响")
    void 普通单_正常过() {
        listener.onMessage(event(1, item(null, null, null, null)));
        PromotionLockCommand cmd = captured();
        assertEquals(1, cmd.getOrderType());
        assertNull(cmd.getActivityId());
        assertNull(cmd.getGroupNo());
    }
}
