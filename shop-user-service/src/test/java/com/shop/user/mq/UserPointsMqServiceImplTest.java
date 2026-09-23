package com.shop.user.mq;

import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.api.pay.enums.PayMethods;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.api.user.dto.AmountCommand;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.GrowthCommand;
import com.shop.api.user.dto.PointsDeductCommand;
import com.shop.api.user.dto.PointsReleaseCommand;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import com.shop.user.account.service.AccountService;
import com.shop.user.account.service.GrowthService;
import com.shop.user.mq.service.MqConsumeService;
import com.shop.user.mq.service.impl.UserPointsMqServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ORDER_PAID / ORDER_CANCELLED / REFUND_SUCCESS 消费：
 * 成功路径、eventId 重复只处理一次、余额退款仅 payMethod=3 触发。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPointsMqServiceImplTest {

    @Mock
    private MqConsumeService consumeService;
    @Mock
    private AccountService accountService;
    @Mock
    private GrowthService growthService;
    @Mock
    private UserMapper userMapper;
    @InjectMocks
    private UserPointsMqServiceImpl mqService;

    private PaymentSucceededEvent paidEvent(String eventId, String orderNo, Long userId, Long amountFen) {
        PaymentSucceededEvent e = PaymentSucceededEvent.builder()
                .payNo("P" + orderNo).orderNo(orderNo).userId(userId)
                .payMethod(PayMethods.WECHAT.getCode()).amountFen(amountFen).build();
        e.setEventId(eventId);
        e.setBizNo(orderNo);
        return e;
    }

    private User user(long id, int level) {
        User u = new User();
        u.setId(id);
        u.setLevel(level);
        return u;
    }

    @Test
    void orderPaid_首次消费_L0实付10元_实扣冻结并返10积分加10成长值() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString())).thenReturn(true);
        when(userMapper.selectById(1001L)).thenReturn(user(1001L, 0));

        mqService.handleOrderPaid(paidEvent("evt-1", "O20260916001", 1001L, 1000L));

        verify(accountService).deductPoints(any(PointsDeductCommand.class));

        ArgumentCaptor<GrantPointsCommand> pointsCaptor = ArgumentCaptor.forClass(GrantPointsCommand.class);
        verify(accountService).grantPoints(pointsCaptor.capture());
        assertEquals("O20260916001", pointsCaptor.getValue().getBizNo());
        assertEquals(10L, pointsCaptor.getValue().getPoints());

        ArgumentCaptor<GrowthCommand> growthCaptor = ArgumentCaptor.forClass(GrowthCommand.class);
        verify(growthService).addGrowth(growthCaptor.capture());
        assertEquals(10, growthCaptor.getValue().getGrowth());
    }

    @Test
    void orderPaid_L1倍率1点1_1元5毛返2积分_四舍五入() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString())).thenReturn(true);
        when(userMapper.selectById(1001L)).thenReturn(user(1001L, 1));

        // 1.5 元 × 1.1 = 1.65，HALF_UP 四舍五入为 2 积分
        mqService.handleOrderPaid(paidEvent("evt-2", "O20260916002", 1001L, 150L));

        ArgumentCaptor<GrantPointsCommand> captor = ArgumentCaptor.forClass(GrantPointsCommand.class);
        verify(accountService).grantPoints(captor.capture());
        assertEquals(2L, captor.getValue().getPoints());
    }

    @Test
    void orderPaid_重复eventId_只处理一次_直接跳过() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString())).thenReturn(false);

        mqService.handleOrderPaid(paidEvent("evt-dup", "O20260916003", 1001L, 1000L));

        verify(accountService, never()).deductPoints(any());
        verify(accountService, never()).grantPoints(any());
        verify(growthService, never()).addGrowth(any());
        verify(userMapper, never()).selectById(any());
    }

    @Test
    void orderPaid_用户不存在_仍实扣冻结但不发积分成长值() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString())).thenReturn(true);
        when(userMapper.selectById(404L)).thenReturn(null);

        mqService.handleOrderPaid(paidEvent("evt-3", "O20260916004", 404L, 1000L));

        verify(accountService).deductPoints(any(PointsDeductCommand.class));
        verify(accountService, never()).grantPoints(any());
        verify(growthService, never()).addGrowth(any());
    }

    @Test
    void orderCancelled_首次消费_释放冻结积分() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString())).thenReturn(true);
        OrderCancelledEvent e = OrderCancelledEvent.builder()
                .orderNo("O20260916005").userId(1001L).usedPointsFen(100L).build();
        e.setEventId("evt-4");
        e.setBizNo("O20260916005");

        mqService.handleOrderCancelled(e);

        ArgumentCaptor<PointsReleaseCommand> captor = ArgumentCaptor.forClass(PointsReleaseCommand.class);
        verify(accountService).releasePoints(captor.capture());
        assertEquals("O20260916005", captor.getValue().getBizNo());
        assertEquals(1001L, captor.getValue().getUserId());
    }

    @Test
    void orderCancelled_重复eventId_不重复释放() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString())).thenReturn(false);
        OrderCancelledEvent e = OrderCancelledEvent.builder()
                .orderNo("O20260916005").userId(1001L).build();
        e.setEventId("evt-dup-cancel");

        mqService.handleOrderCancelled(e);

        verify(accountService, never()).releasePoints(any());
    }

    @Test
    void refundSuccess_余额支付_退款入余额_bizNo为退款单号() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString())).thenReturn(true);
        RefundSucceededEvent e = RefundSucceededEvent.builder()
                .refundNo("R20260916001").orderNo("O20260916001").userId(1001L)
                .amountFen(800L).payMethod(PayMethods.BALANCE.getCode()).build();
        e.setEventId("evt-5");
        e.setBizNo("R20260916001");

        mqService.handleRefundSuccess(e);

        ArgumentCaptor<AmountCommand> captor = ArgumentCaptor.forClass(AmountCommand.class);
        verify(accountService).creditMoney(captor.capture());
        assertEquals("R20260916001", captor.getValue().getBizNo());
        assertEquals(800L, captor.getValue().getAmountFen());
        assertEquals(1001L, captor.getValue().getUserId());
    }

    @Test
    void refundSuccess_微信支付_不退入余额() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString())).thenReturn(true);
        RefundSucceededEvent e = RefundSucceededEvent.builder()
                .refundNo("R20260916002").orderNo("O20260916002").userId(1001L)
                .amountFen(800L).payMethod(PayMethods.WECHAT.getCode()).build();
        e.setEventId("evt-6");

        mqService.handleRefundSuccess(e);

        verify(accountService, never()).creditMoney(any());
    }

    @Test
    void refundSuccess_重复eventId_不重复入账() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString())).thenReturn(false);
        RefundSucceededEvent e = RefundSucceededEvent.builder()
                .refundNo("R20260916003").userId(1001L).amountFen(800L)
                .payMethod(PayMethods.BALANCE.getCode()).build();
        e.setEventId("evt-dup-refund");

        mqService.handleRefundSuccess(e);

        verify(accountService, never()).creditMoney(any());
    }
}
