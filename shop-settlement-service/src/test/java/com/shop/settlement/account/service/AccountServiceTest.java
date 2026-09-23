package com.shop.settlement.account.service;

import com.shop.api.settlement.enums.AccountRole;
import com.shop.common.exception.BizException;
import com.shop.settlement.account.entity.SettAccount;
import com.shop.settlement.account.entity.SettAccountFlow;
import com.shop.settlement.account.mapper.AccountFlowMapper;
import com.shop.settlement.account.mapper.AccountMapper;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.support.SettleNoGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资金账户记账单测：条件更新防透支、(biz_no, change_type) 流水幂等、初始化并发。
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountMapper accountMapper;
    @Mock private AccountFlowMapper flowMapper;
    @Mock private SettleNoGenerator noGenerator;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(accountMapper, flowMapper, noGenerator);
    }

    private SettAccount account(long available, long frozen, long pending) {
        SettAccount a = new SettAccount();
        a.setOwnerId(777L);
        a.setRoleType(AccountRole.MERCHANT);
        a.setAvailableFen(available);
        a.setFrozenFen(frozen);
        a.setPendingSettleFen(pending);
        return a;
    }

    @Test
    @DisplayName("getOrCreate_已存在_直接返回不新建")
    void getOrCreate_exists() {
        when(accountMapper.selectOne(any())).thenReturn(account(100, 0, 0));
        SettAccount a = service.getOrCreate(777L, AccountRole.MERCHANT);
        assertEquals(100L, a.getAvailableFen());
        verify(accountMapper, never()).insert(any());
    }

    @Test
    @DisplayName("getOrCreate_并发唯一键冲突_重查返回")
    void getOrCreate_duplicateKey_reselect() {
        SettAccount existed = account(50, 0, 0);
        when(accountMapper.selectOne(any())).thenReturn(null, existed);
        when(accountMapper.insert(any())).thenThrow(new DuplicateKeyException("uk"));
        assertEquals(50L, service.getOrCreate(777L, AccountRole.MERCHANT).getAvailableFen());
    }

    @Test
    @DisplayName("creditPending_流水已存在或金额0_幂等不入账")
    void creditPending_idempotent() {
        when(flowMapper.selectCount(any())).thenReturn(1L);
        service.creditPending(777L, AccountRole.MERCHANT, "CL1",
                FlowChangeTypes.CLEARING_TO_PENDING, 1000L, "");
        service.creditPending(777L, AccountRole.MERCHANT, "CL2",
                FlowChangeTypes.CLEARING_TO_PENDING, 0L, "");
        verify(accountMapper, never()).creditPending(anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyLong());
        verify(flowMapper, never()).insert(any());
    }

    @Test
    @DisplayName("creditPending_正常_条件更新并写带余额快照的流水")
    void creditPending_success_writesFlow() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectCount(any())).thenReturn(1L);
        when(accountMapper.creditPending(777L, AccountRole.MERCHANT, 1000L)).thenReturn(1);
        when(accountMapper.selectOne(any())).thenReturn(account(0, 0, 1000L));
        when(noGenerator.nextFlowNo()).thenReturn("F1");

        service.creditPending(777L, AccountRole.MERCHANT, "CL1",
                FlowChangeTypes.CLEARING_TO_PENDING, 1000L, "入账");

        ArgumentCaptor<SettAccountFlow> cap = ArgumentCaptor.forClass(SettAccountFlow.class);
        verify(flowMapper).insert(cap.capture());
        SettAccountFlow f = cap.getValue();
        assertEquals("F1", f.getFlowNo());
        assertEquals("CL1", f.getBizNo());
        assertEquals(FlowChangeTypes.CLEARING_TO_PENDING, f.getChangeType());
        assertEquals(1000L, f.getPendingChange());
        assertEquals(1000L, f.getPendingAfter());
    }

    @Test
    @DisplayName("pendingToAvailable_待结算不足条件更新0行_抛结算金额异常")
    void pendingToAvailable_insufficient_throw() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.pendingToAvailable(777L, AccountRole.MERCHANT, 1000L)).thenReturn(0);
        BizException ex = assertThrows(BizException.class, () -> service.pendingToAvailable(
                777L, AccountRole.MERCHANT, "CL1", FlowChangeTypes.PENDING_TO_AVAILABLE, 1000L, ""));
        assertEquals(70001, ex.getCode());
    }

    @Test
    @DisplayName("freeze_可用余额不足_抛提现限制异常")
    void freeze_insufficient_throw() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.freeze(777L, AccountRole.MERCHANT, 100_000L)).thenReturn(0);
        BizException ex = assertThrows(BizException.class,
                () -> service.freeze(777L, AccountRole.MERCHANT, "WD1", 100_000L, ""));
        assertEquals(70003, ex.getCode());
    }

    @Test
    @DisplayName("freeze_冻结流水已存在_幂等直接返回")
    void freeze_flowExists_idempotent() {
        when(flowMapper.selectCount(any())).thenReturn(1L);
        service.freeze(777L, AccountRole.MERCHANT, "WD1", 100_000L, "");
        verify(accountMapper, never()).freeze(anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyLong());
    }

    @Test
    @DisplayName("debitPendingPartial_待结算不足need_原子部分扣减返回实际值并写实际扣减流水(P1-12)")
    void debitPendingPartial_insufficient_partialActual() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        // FOR UPDATE 锁内读到待结算仅 20，请求扣 50
        when(accountMapper.selectForUpdate(777L, AccountRole.MERCHANT))
                .thenReturn(account(0, 0, 20));
        when(accountMapper.debitPendingPartial(777L, AccountRole.MERCHANT, 50L)).thenReturn(1);
        // writeFlow 余额快照回读：扣减后待结算为 0
        when(accountMapper.selectOne(any())).thenReturn(account(0, 0, 0));
        when(noGenerator.nextFlowNo()).thenReturn("FP1");

        long actual = service.debitPendingPartial(777L, AccountRole.MERCHANT, "RF1",
                FlowChangeTypes.REFUND_FROM_PENDING, 50L, "");

        assertEquals(20L, actual);
        // SQL 以 need=50 下发（LEAST 在库内行级计算），服务层不把读到的 20 拼进 SQL
        verify(accountMapper).debitPendingPartial(777L, AccountRole.MERCHANT, 50L);
        ArgumentCaptor<SettAccountFlow> cap = ArgumentCaptor.forClass(SettAccountFlow.class);
        verify(flowMapper).insert(cap.capture());
        assertEquals(-20L, cap.getValue().getPendingChange());
        assertEquals(0L, cap.getValue().getPendingAfter());
    }

    @Test
    @DisplayName("debitPendingPartial_锁内待结算为0_返回0不执行扣减SQL")
    void debitPendingPartial_zeroBalance_returnZero() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectForUpdate(777L, AccountRole.MERCHANT))
                .thenReturn(account(100, 0, 0));

        long actual = service.debitPendingPartial(777L, AccountRole.MERCHANT, "RF2",
                FlowChangeTypes.REFUND_FROM_PENDING, 50L, "");

        assertEquals(0L, actual);
        verify(accountMapper, never()).debitPendingPartial(anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyLong());
        verify(flowMapper, never()).insert(any());
    }

    @Test
    @DisplayName("debitPendingPartial_流水已存在或need非正_幂等返回0")
    void debitPendingPartial_idempotent() {
        when(flowMapper.selectCount(any())).thenReturn(1L);
        assertEquals(0L, service.debitPendingPartial(777L, AccountRole.MERCHANT, "RF3",
                FlowChangeTypes.REFUND_FROM_PENDING, 50L, ""));
        assertEquals(0L, service.debitPendingPartial(777L, AccountRole.MERCHANT, "RF4",
                FlowChangeTypes.REFUND_FROM_PENDING, 0L, ""));
        verify(accountMapper, never()).selectForUpdate(anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(accountMapper, never()).debitPendingPartial(anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyLong());
    }

    @Test
    @DisplayName("debitAvailablePartial_可提现不足_部分扣减返回实际值(P1-10 第二档)")
    void debitAvailablePartial_partialActual() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectForUpdate(777L, AccountRole.MERCHANT))
                .thenReturn(account(30, 0, 0));
        when(accountMapper.debitAvailablePartial(777L, AccountRole.MERCHANT, 100L)).thenReturn(1);
        when(accountMapper.selectOne(any())).thenReturn(account(0, 0, 0));
        when(noGenerator.nextFlowNo()).thenReturn("FA1");

        long actual = service.debitAvailablePartial(777L, AccountRole.MERCHANT, "RF5",
                FlowChangeTypes.REFUND_FROM_AVAILABLE, 100L, "");

        assertEquals(30L, actual);
        verify(accountMapper).debitAvailablePartial(777L, AccountRole.MERCHANT, 100L);
        ArgumentCaptor<SettAccountFlow> cap = ArgumentCaptor.forClass(SettAccountFlow.class);
        verify(flowMapper).insert(cap.capture());
        assertEquals(-30L, cap.getValue().getAvailableChange());
    }

    @Test
    @DisplayName("unfreezeBack_提现退回_条件更新成功写冻结变动流水")
    void unfreezeBack_success() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.unfreezeBack(777L, AccountRole.MERCHANT, 100_000L)).thenReturn(1);
        when(accountMapper.selectOne(any())).thenReturn(account(100_000L, 0L, 0L));
        when(noGenerator.nextFlowNo()).thenReturn("F2");

        service.unfreezeBack(777L, AccountRole.MERCHANT, "WD9",
                FlowChangeTypes.WITHDRAW_RETURN, 100_000L, "退回");

        ArgumentCaptor<SettAccountFlow> cap = ArgumentCaptor.forClass(SettAccountFlow.class);
        verify(flowMapper).insert(cap.capture());
        assertEquals(100_000L, cap.getValue().getAvailableChange());
        assertEquals(-100_000L, cap.getValue().getFrozenChange());
    }

    @Test
    @DisplayName("writeZeroFlow_幂等_仅首次留痕")
    void writeZeroFlow_idempotent() {
        when(flowMapper.selectCount(any())).thenReturn(1L);
        service.writeZeroFlow(777L, AccountRole.MERCHANT, "RF1",
                FlowChangeTypes.REFUND_FROM_DEPOSIT, "");
        verify(flowMapper, never()).insert(any());
    }

    @Test
    @DisplayName("getOrCreate_不存在_插入零余额账户")
    void getOrCreate_new_insertZeroBalances() {
        when(accountMapper.selectOne(any())).thenReturn(null);
        SettAccount a = service.getOrCreate(888L, AccountRole.MARKETING);
        assertEquals(0L, a.getAvailableFen());
        assertEquals(0L, a.getFrozenFen());
        assertEquals(0L, a.getPendingSettleFen());
        verify(accountMapper).insert(any());
    }

    @Test
    @DisplayName("creditAvailable_平台收入入账成功写正向流水")
    void creditAvailable_success() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectCount(any())).thenReturn(1L);
        when(accountMapper.creditAvailable(0L, AccountRole.PLATFORM, 200L)).thenReturn(1);
        when(accountMapper.selectOne(any())).thenReturn(account(200L, 0L, 0L));
        when(noGenerator.nextFlowNo()).thenReturn("F3");

        service.creditAvailable(0L, AccountRole.PLATFORM, "WD1",
                FlowChangeTypes.WITHDRAW_FEE_INCOME, 200L, "手续费");

        ArgumentCaptor<SettAccountFlow> cap = ArgumentCaptor.forClass(SettAccountFlow.class);
        verify(flowMapper).insert(cap.capture());
        assertEquals(200L, cap.getValue().getAvailableChange());
        assertEquals(200L, cap.getValue().getAvailableAfter());
    }

    @Test
    @DisplayName("creditAvailable_条件更新0行_抛系统异常")
    void creditAvailable_fail_throw() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectCount(any())).thenReturn(1L);
        when(accountMapper.creditAvailable(0L, AccountRole.PLATFORM, 200L)).thenReturn(0);
        assertThrows(BizException.class, () -> service.creditAvailable(0L, AccountRole.PLATFORM,
                "WD1", FlowChangeTypes.WITHDRAW_FEE_INCOME, 200L, ""));
        verify(flowMapper, never()).insert(any());
    }

    @Test
    @DisplayName("debitAvailable_营销出账成功写负向流水")
    void debitAvailable_success() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectCount(any())).thenReturn(1L);
        when(accountMapper.debitAvailable(0L, AccountRole.MARKETING, 500L)).thenReturn(1);
        when(accountMapper.selectOne(any())).thenReturn(account(0L, 0L, 0L));
        when(noGenerator.nextFlowNo()).thenReturn("F4");

        service.debitAvailable(0L, AccountRole.MARKETING, "CL1",
                FlowChangeTypes.MARKETING_SUBSIDY_OUT, 500L, "补贴出账");

        ArgumentCaptor<SettAccountFlow> cap = ArgumentCaptor.forClass(SettAccountFlow.class);
        verify(flowMapper).insert(cap.capture());
        assertEquals(-500L, cap.getValue().getAvailableChange());
    }

    @Test
    @DisplayName("creditPending_条件更新0行_抛系统异常")
    void creditPending_fail_throw() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectCount(any())).thenReturn(1L);
        when(accountMapper.creditPending(777L, AccountRole.MERCHANT, 1000L)).thenReturn(0);
        assertThrows(BizException.class, () -> service.creditPending(777L, AccountRole.MERCHANT,
                "CL9", FlowChangeTypes.CLEARING_TO_PENDING, 1000L, ""));
    }

    @Test
    @DisplayName("unfreezeOut_打款成功_冻结出账写流水；0行抛异常")
    void unfreezeOut_cases() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.unfreezeOut(777L, AccountRole.MERCHANT, 100_000L)).thenReturn(1);
        when(accountMapper.selectOne(any())).thenReturn(account(0L, 0L, 0L));
        when(noGenerator.nextFlowNo()).thenReturn("F5");

        service.unfreezeOut(777L, AccountRole.MERCHANT, "WD1", 100_000L, "出款");
        ArgumentCaptor<SettAccountFlow> cap = ArgumentCaptor.forClass(SettAccountFlow.class);
        verify(flowMapper).insert(cap.capture());
        assertEquals(-100_000L, cap.getValue().getFrozenChange());

        when(accountMapper.unfreezeOut(777L, AccountRole.MERCHANT, 500L)).thenReturn(0);
        assertThrows(BizException.class, () -> service.unfreezeOut(777L, AccountRole.MERCHANT,
                "WD2", 500L, ""));
    }

    @Test
    @DisplayName("writeZeroFlow_首次_建账户并留痕")
    void writeZeroFlow_firstTime_insert() {
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectCount(any())).thenReturn(0L);
        when(accountMapper.selectOne(any())).thenReturn(null);
        when(noGenerator.nextFlowNo()).thenReturn("F6");

        service.writeZeroFlow(777L, AccountRole.MERCHANT, "RF9",
                FlowChangeTypes.REFUND_FROM_DEPOSIT, "保证金扣赔");

        verify(accountMapper).insert(any());
        ArgumentCaptor<SettAccountFlow> cap = ArgumentCaptor.forClass(SettAccountFlow.class);
        verify(flowMapper).insert(cap.capture());
        assertEquals(0L, cap.getValue().getAvailableChange());
        assertEquals(0L, cap.getValue().getFrozenChange());
        assertEquals(0L, cap.getValue().getPendingChange());
    }
}
