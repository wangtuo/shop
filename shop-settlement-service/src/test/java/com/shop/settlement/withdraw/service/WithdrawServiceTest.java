package com.shop.settlement.withdraw.service;

import com.shop.api.settlement.enums.AccountRole;
import com.shop.api.settlement.enums.WithdrawStatuses;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.settlement.account.entity.SettAccount;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.engine.WithdrawCalculator;
import com.shop.settlement.enums.AutoFrequencies;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.enums.MerchantStatuses;
import com.shop.settlement.enums.WithdrawChannels;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.remit.RemitChannelClient;
import com.shop.settlement.remit.RemitQueryResult;
import com.shop.settlement.remit.RemitResult;
import com.shop.settlement.remit.RemitRouter;
import com.shop.settlement.support.DataCipher;
import com.shop.settlement.support.LambdaTableSupport;
import com.shop.settlement.support.SettleNoGenerator;
import com.shop.settlement.withdraw.dto.ApplyWithdrawRequest;
import com.shop.settlement.withdraw.entity.SettWithdraw;
import com.shop.settlement.withdraw.entity.SettWithdrawAutoConfig;
import com.shop.settlement.withdraw.entity.SettWithdrawDailyCount;
import com.shop.settlement.withdraw.mapper.WithdrawAutoConfigMapper;
import com.shop.settlement.withdraw.mapper.WithdrawDailyCountMapper;
import com.shop.settlement.withdraw.mapper.WithdrawMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提现服务单测（design 7.4）：手续费/限额、保证金 50% 限提、冻结失败、
 * T+1 批次打款（手续费入平台、成功事件）、失败/拒绝退回、审核批次、自动提现调度。
 */
@ExtendWith(MockitoExtension.class)
class WithdrawServiceTest {

    @Mock private WithdrawMapper withdrawMapper;
    @Mock private WithdrawDailyCountMapper countMapper;
    @Mock private WithdrawAutoConfigMapper autoConfigMapper;
    @Mock private MerchantService merchantService;
    @Mock private DepositService depositService;
    @Mock private AccountService accountService;
    @Mock private DistributedLockTemplate lockTemplate;
    @Mock private SettleNoGenerator noGenerator;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private RemitRouter remitRouter;

    private DataCipher cipher;
    private WithdrawService service;

    @BeforeAll
    static void initLambdaCache() {
        LambdaTableSupport.init();
    }

    @BeforeEach
    void setUp() {
        cipher = DataCipher.forTest(DataCipher.DEV_DEFAULT_KEY, "");
        service = new WithdrawService(withdrawMapper, countMapper, autoConfigMapper, merchantService,
                depositService, accountService, new WithdrawCalculator(), lockTemplate,
                noGenerator, outboxPublisher, cipher, remitRouter);
        // 编排方法经代理调短事务方法：单测内自引用指向自身
        ReflectionTestUtils.setField(service, "self", service);
        lenient().doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get())
                .when(lockTemplate).execute(any(), any(java.util.function.Supplier.class));
    }

    private SettMerchant merchant(long id) {
        SettMerchant m = new SettMerchant();
        m.setId(id);
        m.setStatus(MerchantStatuses.NORMAL);
        m.setDepositRequiredFen(200_000L);
        m.setDepositBalanceFen(200_000L);
        return m;
    }

    private ApplyWithdrawRequest request(long amount, int channel) {
        ApplyWithdrawRequest req = new ApplyWithdrawRequest();
        req.setAmountFen(amount);
        req.setChannel(channel);
        req.setChannelAccount("6222");
        req.setAccountName("张三");
        req.setBankName("招商银行");
        return req;
    }

    private void stubDailyCount(Long dailyAmount, int monthApplied) {
        SettWithdrawDailyCount count = new SettWithdrawDailyCount();
        count.setDailyAmountFen(dailyAmount);
        when(countMapper.selectOne(any())).thenReturn(count);
        when(countMapper.sumMonthApplyCount(eq(777L), any())).thenReturn(monthApplied);
    }

    @Test
    @DisplayName("apply_当月第4笔_冻结金额并按最低2元收手续费")
    void apply_fourthOfMonth_minFee() {
        when(merchantService.requireActiveMerchant(777L)).thenReturn(merchant(777L));
        stubDailyCount(300_000L, 3);
        when(depositService.belowAlertThreshold(any())).thenReturn(false);
        when(noGenerator.nextWithdrawNo()).thenReturn("WD1");

        SettWithdraw w = service.apply(777L, request(100_000L, WithdrawChannels.BANK_CARD), false);

        assertEquals(WithdrawStatuses.APPLY, w.getStatus());
        assertEquals(200L, w.getFeeFen());        // 100元*0.1%=10分 < 最低200分
        assertEquals(0, w.getFreeOfCharge());
        assertEquals(LocalDate.now(), w.getApplyDate());
        // M-1：落库账号/姓名为密文
        org.junit.jupiter.api.Assertions.assertTrue(cipher.isEncrypted(w.getChannelAccount()));
        assertEquals("6222", cipher.decrypt(w.getChannelAccount()));
        assertEquals("张三", cipher.decrypt(w.getAccountName()));
        verify(accountService).freeze(777L, AccountRole.MERCHANT, "WD1", 100_000L, "提现申请冻结");
        verify(withdrawMapper).insert(w);
        verify(countMapper).bumpOnApply(eq(777L), any(), eq(LocalDate.now()), eq(100_000L), eq(1));
    }

    @Test
    @DisplayName("apply_当月首笔_免手续费且bump charged=0")
    void apply_firstOfMonth_free() {
        when(merchantService.requireActiveMerchant(777L)).thenReturn(merchant(777L));
        stubDailyCount(null, 0);
        when(depositService.belowAlertThreshold(any())).thenReturn(false);
        when(noGenerator.nextWithdrawNo()).thenReturn("WD2");

        SettWithdraw w = service.apply(777L, request(5_000_000L, WithdrawChannels.ALIPAY), false);

        assertEquals(0L, w.getFeeFen());
        assertEquals(1, w.getFreeOfCharge());
        verify(countMapper).bumpOnApply(eq(777L), any(), eq(LocalDate.now()), eq(5_000_000L), eq(0));
    }

    @Test
    @DisplayName("apply_保证金低于50%_拒绝提现不冻结")
    void apply_depositBelowThreshold_block() {
        when(merchantService.requireActiveMerchant(777L)).thenReturn(merchant(777L));
        stubDailyCount(0L, 0);
        when(depositService.belowAlertThreshold(any())).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> service.apply(777L, request(100_000L, 1), false));
        assertEquals(70003, ex.getCode());
        verify(accountService, never()).freeze(anyLong(), anyInt(), any(), anyLong(), any());
        verify(withdrawMapper, never()).insert(any());
    }

    @Test
    @DisplayName("apply_可提现余额不足冻结失败_提现单不生成")
    void apply_freezeFail_noInsert() {
        when(merchantService.requireActiveMerchant(777L)).thenReturn(merchant(777L));
        stubDailyCount(0L, 0);
        when(depositService.belowAlertThreshold(any())).thenReturn(false);
        when(noGenerator.nextWithdrawNo()).thenReturn("WD3");
        org.mockito.Mockito.doThrow(new BizException(com.shop.common.exception.ErrorCode.WITHDRAW_LIMIT, "余额不足"))
                .when(accountService).freeze(anyLong(), anyInt(), any(), anyLong(), any());

        assertThrows(BizException.class,
                () -> service.apply(777L, request(100_000L, 1), false));
        verify(withdrawMapper, never()).insert(any());
        verify(countMapper, never()).bumpOnApply(anyLong(), any(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("apply_单日累计超50万_拒单")
    void apply_dailyCapExceed_block() {
        when(merchantService.requireActiveMerchant(777L)).thenReturn(merchant(777L));
        stubDailyCount(49_990_000L, 1);

        BizException ex = assertThrows(BizException.class,
                () -> service.apply(777L, request(20_000L, 1), false));
        assertEquals(70003, ex.getCode());
        verify(accountService, never()).freeze(anyLong(), anyInt(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("apply_非法渠道_参数异常")
    void apply_invalidChannel_throw() {
        assertThrows(BizException.class,
                () -> service.apply(777L, request(100_000L, 9), false));
        verify(merchantService, never()).requireActiveMerchant(anyLong());
    }

    private SettWithdraw withdraw(long id, String no, long merchantId, long amount, long fee, int status) {
        SettWithdraw w = new SettWithdraw();
        w.setId(id);
        w.setWithdrawNo(no);
        w.setMerchantId(merchantId);
        w.setAmountFen(amount);
        w.setFeeFen(fee);
        w.setStatus(status);
        w.setApplyDate(LocalDate.now().minusDays(1));
        w.setChannel(WithdrawChannels.BANK_CARD);
        w.setChannelAccount(cipher.encrypt("6222020200001234"));
        w.setAccountName(cipher.encrypt("张三"));
        w.setBankName("招商银行");
        return w;
    }

    @Test
    @DisplayName("remit提交批_事务外渠道受理_落channelRemitNo状态保持20_不释放冻结不发结果事件")
    void remitBatch_acceptOnly_keepsAuditing() {
        SettWithdraw w = withdraw(1L, "WD1", 777L, 100_000L, 200L, WithdrawStatuses.AUDITING);
        when(withdrawMapper.selectRemittable(LocalDate.now(), WithdrawService.BATCH_LIMIT))
                .thenReturn(List.of(w));
        RemitChannelClient client = org.mockito.Mockito.mock(RemitChannelClient.class);
        when(remitRouter.route(WithdrawChannels.BANK_CARD)).thenReturn(client);
        when(client.remit(any())).thenReturn(RemitResult.accepted("CHWD1"));
        when(withdrawMapper.casMarkRemitNo(1L, "CHWD1")).thenReturn(1);

        int n = service.remitBatch(LocalDate.now());

        assertEquals(1, n);
        assertEquals(WithdrawStatuses.AUDITING, w.getStatus());
        verify(accountService, never()).unfreezeOut(anyLong(), anyInt(), any(), anyLong(), any());
        verify(accountService, never()).creditAvailable(anyLong(), anyInt(), any(),
                eq(FlowChangeTypes.WITHDRAW_FEE_INCOME), anyLong(), any());
        verify(outboxPublisher, never()).publish(eq(MqTopics.WITHDRAW_RESULT), any(), any(), any());
    }

    @Test
    @DisplayName("remit提交_渠道异常/受理拒绝/受理CAS竞争失败_状态保持20无渠道号可重试")
    void remitSubmit_channelFailOrCasLost_retryable() {
        SettWithdraw w = withdraw(2L, "WD2", 777L, 500_000L, 0L, WithdrawStatuses.AUDITING);
        when(withdrawMapper.selectRemittable(LocalDate.now(), WithdrawService.BATCH_LIMIT))
                .thenReturn(List.of(w));
        RemitChannelClient client = org.mockito.Mockito.mock(RemitChannelClient.class);
        when(remitRouter.route(anyInt())).thenReturn(client);

        // 渠道抛异常：不持事务、不落终态（doThrow 风格，避免重桩时调用命中旧桩）
        org.mockito.Mockito.doThrow(new RuntimeException("connection reset"))
                .when(client).remit(any());
        assertEquals(0, service.remitBatch(LocalDate.now()));
        verify(withdrawMapper, never()).casMarkRemitNo(anyLong(), any());

        // 受理拒绝
        org.mockito.Mockito.doReturn(RemitResult.rejected("渠道维护")).when(client).remit(any());
        assertEquals(0, service.remitBatch(LocalDate.now()));
        verify(withdrawMapper, never()).casMarkRemitNo(anyLong(), any());

        // 受理成功但 CAS 失败（他节点已登记）：不计受理、不记账
        org.mockito.Mockito.doReturn(RemitResult.accepted("CHWD2")).when(client).remit(any());
        when(withdrawMapper.casMarkRemitNo(2L, "CHWD2")).thenReturn(0);
        assertEquals(0, service.remitBatch(LocalDate.now()));
        verify(accountService, never()).unfreezeOut(anyLong(), anyInt(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("remit查询批_渠道确认成功且CAS20到30获胜_解冻出款+手续费23+成功事件各一次")
    void queryRemit_success_terminalOnce() {
        SettWithdraw w = withdraw(1L, "WD1", 777L, 100_000L, 200L, WithdrawStatuses.AUDITING);
        w.setChannelRemitNo("CHWD1");
        when(withdrawMapper.selectRemitQueryPending(any(), eq(WithdrawService.BATCH_LIMIT)))
                .thenReturn(List.of(w));
        when(withdrawMapper.touchQuery(eq("WD1"), any())).thenReturn(1);
        RemitChannelClient client = org.mockito.Mockito.mock(RemitChannelClient.class);
        when(remitRouter.route(WithdrawChannels.BANK_CARD)).thenReturn(client);
        when(client.query(any())).thenReturn(RemitQueryResult.success("CHWD1"));
        when(withdrawMapper.casRemitSuccess(1L)).thenReturn(1);

        assertEquals(1, service.queryPendingRemits(java.time.LocalDateTime.now()));

        verify(accountService).unfreezeOut(777L, AccountRole.MERCHANT, "WD1", 100_000L, "提现渠道打款成功");
        verify(accountService).creditAvailable(0L, AccountRole.PLATFORM, "WD1",
                FlowChangeTypes.WITHDRAW_FEE_INCOME, 200L, "提现手续费收入");
        verify(outboxPublisher).publish(eq(MqTopics.WITHDRAW_RESULT), any(), any(), eq("WD1"));

        // 重放：CAS 失败零副作用（出款/事件不重复）
        when(withdrawMapper.casRemitSuccess(1L)).thenReturn(0);
        org.junit.jupiter.api.Assertions.assertFalse(service.confirmRemitSuccess(w));
        verify(accountService, org.mockito.Mockito.times(1))
                .unfreezeOut(anyLong(), anyInt(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("remit查询_处理中保持20；失败markFailed退冻结22发失败事件可重新申请")
    void queryRemit_processingAndFail() {
        SettWithdraw w = withdraw(3L, "WD3", 777L, 100_000L, 0L, WithdrawStatuses.AUDITING);
        w.setChannelRemitNo("CHWD3");
        when(withdrawMapper.selectRemitQueryPending(any(), eq(WithdrawService.BATCH_LIMIT)))
                .thenReturn(List.of(w));
        when(withdrawMapper.touchQuery(eq("WD3"), any())).thenReturn(1);
        RemitChannelClient client = org.mockito.Mockito.mock(RemitChannelClient.class);
        when(remitRouter.route(WithdrawChannels.BANK_CARD)).thenReturn(client);

        // 处理中：不推进
        when(client.query(any())).thenReturn(RemitQueryResult.processing());
        assertEquals(0, service.queryPendingRemits(java.time.LocalDateTime.now()));
        verify(withdrawMapper, never()).casRemitSuccess(anyLong());
        verify(withdrawMapper, never()).update(any(), any());

        // 终态失败：markFailed 20→40、冻结退回 22、失败事件
        when(client.query(any())).thenReturn(RemitQueryResult.fail("CHWD3", "账户异常"));
        when(withdrawMapper.selectOne(any())).thenReturn(w);
        when(withdrawMapper.update(any(), any())).thenReturn(1);
        service.queryPendingRemits(java.time.LocalDateTime.now());
        verify(accountService).unfreezeBack(777L, AccountRole.MERCHANT, "WD3",
                FlowChangeTypes.WITHDRAW_RETURN, 100_000L, "提现失败余额退回");
        verify(outboxPublisher).publish(eq(MqTopics.WITHDRAW_RESULT), any(), any(), eq("WD3"));
    }

    @Test
    @DisplayName("remit查询_touch未获胜不查渠道；查询异常下轮重试不置终态")
    void queryRemit_touchLostOrQueryError() {
        SettWithdraw w = withdraw(4L, "WD4", 777L, 100_000L, 0L, WithdrawStatuses.AUDITING);
        w.setChannelRemitNo("CHWD4");
        when(withdrawMapper.selectRemitQueryPending(any(), eq(WithdrawService.BATCH_LIMIT)))
                .thenReturn(List.of(w));
        when(withdrawMapper.touchQuery(eq("WD4"), any())).thenReturn(0);

        assertEquals(0, service.queryPendingRemits(java.time.LocalDateTime.now()));
        verify(remitRouter, never()).route(anyInt());

        when(withdrawMapper.touchQuery(eq("WD4"), any())).thenReturn(1);
        RemitChannelClient client = org.mockito.Mockito.mock(RemitChannelClient.class);
        when(remitRouter.route(WithdrawChannels.BANK_CARD)).thenReturn(client);
        when(client.query(any())).thenThrow(new RuntimeException("timeout"));
        assertEquals(0, service.queryPendingRemits(java.time.LocalDateTime.now()));
        verify(withdrawMapper, never()).casRemitSuccess(anyLong());
    }

    @Test
    @DisplayName("markFailed_20转40_冻结退回可提现并发失败事件")
    void markFailed_returnFrozen() {
        SettWithdraw w = withdraw(3L, "WD3", 777L, 100_000L, 0L, WithdrawStatuses.AUDITING);
        when(withdrawMapper.selectOne(any())).thenReturn(w);
        when(withdrawMapper.update(any(), any())).thenReturn(1);

        service.markFailed("WD3", "银行卡号有误");

        verify(accountService).unfreezeBack(777L, AccountRole.MERCHANT, "WD3",
                FlowChangeTypes.WITHDRAW_RETURN, 100_000L, "提现失败余额退回");
        verify(outboxPublisher).publish(eq(MqTopics.WITHDRAW_RESULT), any(), any(), eq("WD3"));
    }

    @Test
    @DisplayName("markFailed_状态已非20_抛冲突异常且不退钱")
    void markFailed_wrongStatus_throw() {
        SettWithdraw w = withdraw(4L, "WD4", 777L, 100_000L, 0L, WithdrawStatuses.SUCCESS);
        when(withdrawMapper.selectOne(any())).thenReturn(w);
        when(withdrawMapper.update(any(), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.markFailed("WD4", null));
        verify(accountService, never()).unfreezeBack(anyLong(), anyInt(), any(),
                anyInt(), anyLong(), any());
    }

    @Test
    @DisplayName("refuse_10转50_冻结退回可提现")
    void refuse_returnFrozen() {
        SettWithdraw w = withdraw(5L, "WD5", 777L, 100_000L, 0L, WithdrawStatuses.APPLY);
        when(withdrawMapper.selectOne(any())).thenReturn(w);
        when(withdrawMapper.update(any(), any())).thenReturn(1);

        service.refuse("WD5", "资质不符");

        verify(accountService).unfreezeBack(777L, AccountRole.MERCHANT, "WD5",
                FlowChangeTypes.WITHDRAW_RETURN, 100_000L, "提现拒绝余额退回");
        verify(outboxPublisher).publish(eq(MqTopics.WITHDRAW_RESULT), any(), any(), eq("WD5"));
    }

    @Test
    @DisplayName("auditBatch_并发条件更新_仅统计实际10转20笔数")
    void auditBatch_countsOnlySucceeded() {
        when(withdrawMapper.selectApplying(WithdrawService.BATCH_LIMIT))
                .thenReturn(List.of(
                        withdraw(6L, "WD6", 1L, 100_000L, 0L, WithdrawStatuses.APPLY),
                        withdraw(7L, "WD7", 2L, 100_000L, 0L, WithdrawStatuses.APPLY)));
        when(withdrawMapper.update(any(), any())).thenReturn(1, 0);

        assertEquals(1, service.auditBatch());
    }

    @Test
    @DisplayName("autoWithdraw_每日配置足额_生成自动提现单并记录lastRunDate")
    void autoWithdraw_daily_created() {
        SettWithdrawAutoConfig config = new SettWithdrawAutoConfig();
        config.setMerchantId(777L);
        config.setEnabled(1);
        config.setFrequency(AutoFrequencies.DAILY);
        config.setChannel(WithdrawChannels.BANK_CARD);
        // M-1：配置中为密文，自动打款链路需解密后重新加密落入提现单
        config.setChannelAccount(cipher.encrypt("6222020200001234"));
        config.setAccountName(cipher.encrypt("张三"));
        when(autoConfigMapper.selectEnabled()).thenReturn(List.of(config));
        SettAccount account = new SettAccount();
        account.setAvailableFen(880_000L);
        when(accountService.getOrCreate(777L, AccountRole.MERCHANT)).thenReturn(account);
        when(merchantService.requireActiveMerchant(777L)).thenReturn(merchant(777L));
        stubDailyCount(0L, 0);
        when(depositService.belowAlertThreshold(any())).thenReturn(false);
        when(noGenerator.nextWithdrawNo()).thenReturn("WDA1");

        int created = service.runAutoWithdraw(LocalDate.now());

        assertEquals(1, created);
        assertEquals(LocalDate.now(), config.getLastRunDate());
        verify(autoConfigMapper).updateById(config);
        ArgumentCaptor<SettWithdraw> cap = ArgumentCaptor.forClass(SettWithdraw.class);
        verify(withdrawMapper).insert(cap.capture());
        assertEquals(880_000L, cap.getValue().getAmountFen());
        assertEquals(1, cap.getValue().getAutoWithdraw());
        // M-1：自动链路解密配置账号后以密文落入新提现单，明文不出现在落库字段
        assertEquals("6222020200001234", cipher.decrypt(cap.getValue().getChannelAccount()));
        assertEquals("张三", cipher.decrypt(cap.getValue().getAccountName()));
    }

    @Test
    @DisplayName("autoWithdraw_可提现不足100元_跳过")
    void autoWithdraw_balanceTooLow_skip() {
        SettWithdrawAutoConfig config = new SettWithdrawAutoConfig();
        config.setMerchantId(777L);
        config.setEnabled(1);
        config.setFrequency(AutoFrequencies.DAILY);
        when(autoConfigMapper.selectEnabled()).thenReturn(List.of(config));
        SettAccount account = new SettAccount();
        account.setAvailableFen(9_999L);
        when(accountService.getOrCreate(777L, AccountRole.MERCHANT)).thenReturn(account);

        assertEquals(0, service.runAutoWithdraw(LocalDate.now()));
        verify(withdrawMapper, never()).insert(any());
    }

    @Test
    @DisplayName("autoWithdraw_每周配置星期不匹配或当日已执行_跳过")
    void autoWithdraw_weeklyNotDue_skip() {
        LocalDate today = LocalDate.now();
        SettWithdrawAutoConfig wrongWeekday = new SettWithdrawAutoConfig();
        wrongWeekday.setMerchantId(1L);
        wrongWeekday.setFrequency(AutoFrequencies.WEEKLY);
        wrongWeekday.setWeekday(today.getDayOfWeek().getValue() % 7 + 1); // 另一个星期
        SettWithdrawAutoConfig alreadyRun = new SettWithdrawAutoConfig();
        alreadyRun.setMerchantId(2L);
        alreadyRun.setFrequency(AutoFrequencies.DAILY);
        alreadyRun.setLastRunDate(today);
        when(autoConfigMapper.selectEnabled()).thenReturn(List.of(wrongWeekday, alreadyRun));

        assertEquals(0, service.runAutoWithdraw(today));
        verify(accountService, never()).getOrCreate(anyLong(), anyInt());
        verify(withdrawMapper, never()).insert(any());
    }
}
