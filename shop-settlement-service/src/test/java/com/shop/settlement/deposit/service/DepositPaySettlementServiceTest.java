package com.shop.settlement.deposit.service;

import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.pay.enums.PayScenes;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.common.exception.BizException;
import com.shop.common.constant.MqTopics;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.clearing.service.ShortfallWorkOrderService;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.deposit.mapper.DepositLogMapper;
import com.shop.settlement.enums.DepositLogTypes;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.enums.MerchantStatuses;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.mapper.MerchantMapper;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.mq.service.MqConsumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保证金缴费到账入账单测（B10 步骤 1③）：scene 守卫、mq_consume/DP CAS/流水三重幂等、
 * 金额校验、到账才入余额 + 40 流水 + P0-1 clawback 钩子。
 */
@ExtendWith(MockitoExtension.class)
class DepositPaySettlementServiceTest {

    @Mock private MqConsumeService mqConsumeService;
    @Mock private DepositLogMapper depositLogMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantService merchantService;
    @Mock private AccountService accountService;
    @Mock private ShortfallWorkOrderService shortfallWorkOrderService;

    private DepositPaySettlementService service;

    @BeforeEach
    void setUp() {
        service = new DepositPaySettlementService(mqConsumeService, depositLogMapper, merchantMapper,
                merchantService, accountService, shortfallWorkOrderService);
    }

    private PaymentSucceededEvent event(Integer scene, String payNo, String orderNo, Long amount) {
        return PaymentSucceededEvent.builder()
                .payNo(payNo).orderNo(orderNo).userId(7L).amountFen(amount).payScene(scene).build();
    }

    private SettDepositLog dpLog(String logNo, long amount, int status) {
        SettDepositLog l = new SettDepositLog();
        l.setId(1L);
        l.setLogNo(logNo);
        l.setMerchantId(7L);
        l.setLogType(DepositLogTypes.PAY);
        l.setStatus(status);
        l.setAmountFen(amount);
        l.setPayNo("");
        return l;
    }

    @Test
    @DisplayName("scene非4_不登记消费不入余额(clause守卫)")
    void nonDepositScene_ackOnly() {
        service.onPaymentSucceeded(event(PayScenes.NORMAL, "P1", "O1", 100L));
        service.onPaymentSucceeded(event(null, "P2", "O2", 100L));
        verify(mqConsumeService, never()).tryRecord(any(), any(), any(), any());
        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
    }

    @Test
    @DisplayName("重复eventId_直接ACK零副作用")
    void duplicateEvent_noEffect() {
        when(mqConsumeService.tryRecord(any(), eq(MqTopics.ORDER_PAID),
                eq(DepositPaySettlementService.CG_DEPOSIT_PAY), eq("DP1")))
                .thenReturn(false);
        service.onPaymentSucceeded(event(PayScenes.DEPOSIT, "P1", "DP1", 100L));
        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
        verify(shortfallWorkOrderService, never()).clawbackOnDepositPaid(anyLong());
    }

    @Test
    @DisplayName("DP日志不存在_抛错等MQ重试禁止裸ACK长款；金额不一致抛参数错误")
    void missingLogOrAmountMismatch_throw() {
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(depositLogMapper.selectByPayNo("P1")).thenReturn(null);
        when(depositLogMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class,
                () -> service.onPaymentSucceeded(event(PayScenes.DEPOSIT, "P1", "DP1", 100L)));

        when(depositLogMapper.selectByPayNo("P2")).thenReturn(dpLog("DP2", 200L, 10));
        assertThrows(BizException.class,
                () -> service.onPaymentSucceeded(event(PayScenes.DEPOSIT, "P2", "DP2", 100L)));
        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
    }

    @Test
    @DisplayName("CAS10到20失败_事件重放零副作用不入余额不补扣")
    void casLost_noEffect() {
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(depositLogMapper.selectByPayNo("P1")).thenReturn(dpLog("DP1", 100L, 20));
        when(depositLogMapper.casStatus("DP1", 10, 20)).thenReturn(0);

        service.onPaymentSucceeded(event(PayScenes.DEPOSIT, "P1", "DP1", 100L));

        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
        verify(accountService, never()).writeZeroFlow(anyLong(), anyInt(), any(), anyInt(), any());
        verify(shortfallWorkOrderService, never()).clawbackOnDepositPaid(anyLong());
    }

    @Test
    @DisplayName("到账成功_CAS获胜_余额+amount+40流水恰一笔+触发clawback+补足解除预警")
    void success_settleOnce() {
        SettDepositLog dp = dpLog("DP1", 110_000L, 10);
        SettMerchant post = new SettMerchant();
        post.setId(7L);
        post.setStatus(MerchantStatuses.NORMAL);
        post.setDepositRequiredFen(200_000L);
        post.setDepositBalanceFen(200_000L);
        post.setDepositAlerted(1);
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(depositLogMapper.selectByPayNo("P1")).thenReturn(dp);
        when(depositLogMapper.casStatus("DP1", 10, 20)).thenReturn(1);
        when(merchantMapper.changeDeposit(7L, 110_000L)).thenReturn(1);
        when(merchantService.requireMerchant(7L)).thenReturn(post);

        service.onPaymentSucceeded(event(PayScenes.DEPOSIT, "P1", "DP1", 110_000L));

        verify(merchantMapper).changeDeposit(7L, 110_000L);
        verify(accountService).writeZeroFlow(7L, AccountRole.MERCHANT, "DP1",
                FlowChangeTypes.DEPOSIT_PAY, "保证金缴纳到账");
        verify(shortfallWorkOrderService).clawbackOnDepositPaid(7L);
        verify(merchantMapper).updateById(post);
        org.junit.jupiter.api.Assertions.assertEquals(0, post.getDepositAlerted());
    }
}
