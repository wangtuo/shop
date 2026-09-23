package com.shop.user.account;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.api.user.dto.AmountCommand;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.PointsDeductCommand;
import com.shop.api.user.dto.PointsLockCommand;
import com.shop.api.user.dto.PointsRefundCommand;
import com.shop.api.user.dto.PointsReleaseCommand;
import com.shop.api.user.enums.AccountTypes;
import com.shop.api.user.enums.PointsChangeType;
import com.shop.api.user.enums.PointsScene;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.user.account.entity.UserAccount;
import com.shop.user.account.entity.UserAccountFlow;
import com.shop.user.account.entity.UserPointsFreeze;
import com.shop.user.account.entity.UserPointsGrant;
import com.shop.user.account.mapper.UserAccountFlowMapper;
import com.shop.user.account.mapper.UserAccountMapper;
import com.shop.user.account.mapper.UserPointsDailyMapper;
import com.shop.user.account.mapper.UserPointsFreezeMapper;
import com.shop.user.account.mapper.UserPointsGrantMapper;
import com.shop.user.account.service.impl.AccountServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账户借贷 / 积分 TCC / 每日上限 / 批次过期 / 幂等用例。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceImplTest {

    private static final Long UID = 1001L;

    @Mock
    private UserAccountMapper accountMapper;
    @Mock
    private UserAccountFlowMapper flowMapper;
    @Mock
    private UserPointsFreezeMapper freezeMapper;
    @Mock
    private UserPointsGrantMapper grantMapper;
    @Mock
    private UserPointsDailyMapper dailyMapper;
    @Mock
    private IdGenerator idGenerator;
    @Mock
    private OutboxPublisher outboxPublisher;

    @InjectMocks
    private AccountServiceImpl accountService;

    /**
     * 纯 Mockito 环境下 MyBatis-Plus 不会引导 TableInfo，而 LambdaUpdateWrapper#set
     * 需要解析实体列缓存；测试前手工初始化一次。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, com.shop.user.account.entity.UserPointsFreeze.class);
    }

    @BeforeEach
    void setUp() {
        when(idGenerator.nextId()).thenReturn(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        when(accountMapper.selectByUserType(anyLong(), anyInt())).thenAnswer(inv ->
                account(inv.getArgument(0), inv.getArgument(1), 0L, 0L));
    }

    private UserAccount account(Long userId, int type, long balance, long frozen) {
        UserAccount a = new UserAccount();
        a.setUserId(userId);
        a.setAccountType(type);
        a.setBalance(balance);
        a.setFrozen(frozen);
        return a;
    }

    private AmountCommand amount(String bizNo, long fen) {
        return AmountCommand.builder().userId(UID).bizNo(bizNo).amountFen(fen).remark("r").build();
    }

    // ---------------- 余额 / 赠金 ----------------

    @Test
    void debitBalance_余额充足_扣款记账() {
        when(flowMapper.selectByBizAndType("P1", 2)).thenReturn(null);
        when(accountMapper.debit(UID, AccountTypes.BALANCE, 500L)).thenReturn(1);
        when(accountMapper.selectByUserType(UID, AccountTypes.BALANCE)).thenReturn(account(UID, 1, 1500L, 0L));

        accountService.debitMoney(amount("P1", 500L), AccountTypes.BALANCE);

        verify(accountMapper).debit(UID, AccountTypes.BALANCE, 500L);
        verify(flowMapper).insert(any(UserAccountFlow.class));
    }

    @Test
    void debitBalance_余额不足_抛支付错误且不记账() {
        // P0-1 新契约：先插占位流水（同事务），条件扣款 0 行 → 抛错，占位由事务回滚，不写成功态
        when(accountMapper.debit(UID, AccountTypes.BALANCE, 500L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> accountService.debitMoney(amount("P2", 500L), AccountTypes.BALANCE));
        assertEquals(ErrorCode.PAY_ERROR.getCode(), ex.getCode());
        verify(flowMapper).insert(any(UserAccountFlow.class));
        verify(flowMapper, never()).updateById(any());
    }

    @Test
    void debitGift_赠金不足_抛支付错误() {
        when(flowMapper.selectByBizAndType("P3", 2)).thenReturn(null);
        when(accountMapper.debit(UID, AccountTypes.GIFT, 100L)).thenReturn(0);

        assertThrows(BizException.class,
                () -> accountService.debitMoney(amount("P3", 100L), AccountTypes.GIFT));
    }

    @Test
    void debitBalance_bizNo重复_幂等直接成功不重复记账() {
        // P0-1 新契约：幂等由流水唯一键在「动余额之前」拦截
        when(flowMapper.insert(any(UserAccountFlow.class))).thenThrow(new DuplicateKeyException("uk"));

        accountService.debitMoney(amount("P1", 500L), AccountTypes.BALANCE);

        verify(accountMapper, never()).debit(anyLong(), anyInt(), anyLong());
        verify(flowMapper, never()).updateById(any());
    }

    @Test
    void debitBalance_占位流水唯一键冲突_不动余额() {
        when(flowMapper.insert(any(UserAccountFlow.class))).thenThrow(new DuplicateKeyException("uk"));

        // 不抛异常即幂等成功；余额绝不能被改动（旧实现是先扣款后吞 UK 冲突 → 重复扣款）
        accountService.debitMoney(amount("P9", 100L), AccountTypes.BALANCE);
        verify(accountMapper, never()).debit(anyLong(), anyInt(), anyLong());
    }

    @Test
    void creditBalance_退款入账_贷款记账() {
        when(flowMapper.selectByBizAndType("R1", 1)).thenReturn(null);
        when(accountMapper.credit(UID, AccountTypes.BALANCE, 800L)).thenReturn(1);
        when(accountMapper.selectByUserType(UID, AccountTypes.BALANCE)).thenReturn(account(UID, 1, 800L, 0L));

        accountService.creditMoney(amount("R1", 800L));

        verify(accountMapper).credit(UID, AccountTypes.BALANCE, 800L);
        verify(flowMapper).insert(any(UserAccountFlow.class));
    }

    @Test
    void creditBalance_bizNo重复_幂等跳过() {
        // P0-1：占位流水 UK 冲突 → 直接返回，绝不入账（含不同 eventId 同 refundNo 的并发重复事件）
        when(flowMapper.insert(any(UserAccountFlow.class))).thenThrow(new DuplicateKeyException("uk"));
        accountService.creditMoney(amount("R1", 800L));
        verify(accountMapper, never()).credit(anyLong(), anyInt(), anyLong());
    }

    @Test
    void debitMoney_非正数金额_抛参数错误() {
        assertThrows(BizException.class,
                () -> accountService.debitMoney(amount("P4", 0L), AccountTypes.BALANCE));
    }

    // ---------------- 积分冻结 / 实扣 / 释放 ----------------

    @Test
    void lockPoints_可用充足_冻结并发事件() {
        PointsLockCommand cmd = PointsLockCommand.builder()
                .userId(UID).bizNo("O1").points(100L).deductFen(100L).scene(PointsScene.CONSUME).build();
        when(flowMapper.selectByBizAndType("O1", PointsChangeType.FREEZE)).thenReturn(null);
        when(freezeMapper.selectOne(any())).thenReturn(null);
        when(accountMapper.freezePoints(UID, 100L)).thenReturn(1);
        when(accountMapper.selectByUserType(UID, AccountTypes.POINTS)).thenReturn(account(UID, 3, 900L, 0L));

        accountService.lockPoints(cmd);

        verify(accountMapper).freezePoints(UID, 100L);
        verify(freezeMapper).insert(any(UserPointsFreeze.class));
        verify(flowMapper).insert(any(UserAccountFlow.class));
        // R4-25：outbox 键带变更类型维度，同 bizNo 的冻结/扣减/退回多阶段不再撞 UK
        verify(outboxPublisher).publish(any(), any(), any(), eq("O1#ct3"));
    }

    @Test
    void lockPoints_积分不足_抛错() {
        PointsLockCommand cmd = PointsLockCommand.builder()
                .userId(UID).bizNo("O2").points(100L).deductFen(100L).build();
        when(flowMapper.selectByBizAndType("O2", PointsChangeType.FREEZE)).thenReturn(null);
        when(freezeMapper.selectOne(any())).thenReturn(null);
        when(accountMapper.freezePoints(UID, 100L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> accountService.lockPoints(cmd));
        assertEquals(ErrorCode.POINTS_NOT_ENOUGH.getCode(), ex.getCode());
        verify(freezeMapper, never()).insert(any());
    }

    @Test
    void lockPoints_积分与抵现分不一致_抛参数错误() {
        PointsLockCommand cmd = PointsLockCommand.builder()
                .userId(UID).bizNo("O3").points(100L).deductFen(99L).build();
        assertThrows(BizException.class, () -> accountService.lockPoints(cmd));
    }

    @Test
    void lockPoints_重复bizNo_幂等成功() {
        PointsLockCommand cmd = PointsLockCommand.builder()
                .userId(UID).bizNo("O1").points(100L).deductFen(100L).build();
        when(freezeMapper.selectOne(any())).thenReturn(null);
        when(flowMapper.insert(any(UserAccountFlow.class))).thenThrow(new DuplicateKeyException("uk"));

        accountService.lockPoints(cmd);
        verify(accountMapper, never()).freezePoints(anyLong(), anyLong());
    }

    @Test
    void deductPoints_冻结存在_FIFO核销批次() {
        UserPointsFreeze freeze = new UserPointsFreeze();
        freeze.setId(9L);
        freeze.setUserId(UID);
        freeze.setOrderNo("O1");
        freeze.setPoints(100L);
        freeze.setStatus(0);
        freeze.setScene(PointsScene.CONSUME);
        when(freezeMapper.selectOne(any())).thenReturn(freeze);
        when(flowMapper.selectByBizAndType("O1", PointsChangeType.CONSUME)).thenReturn(null);
        when(freezeMapper.update(any(), any())).thenReturn(1);
        when(accountMapper.deductFrozen(UID, 100L)).thenReturn(1);
        UserPointsGrant batch = new UserPointsGrant();
        batch.setId(77L);
        batch.setPointsRemaining(100L);
        when(grantMapper.selectFifoAvailable(UID)).thenReturn(List.of(batch));
        when(grantMapper.consumeRemaining(77L, 100L)).thenReturn(1);
        when(accountMapper.selectByUserType(UID, AccountTypes.POINTS)).thenReturn(account(UID, 3, 900L, 0L));

        accountService.deductPoints(PointsDeductCommand.builder().userId(UID).bizNo("O1").build());

        verify(grantMapper).consumeRemaining(77L, 100L);
        verify(flowMapper).insert(any(UserAccountFlow.class));
        // R4-25：同订单冻结(#ct3)后扣减(#ct2)，键各自唯一
        verify(outboxPublisher).publish(any(), any(), any(), eq("O1#ct2"));
    }

    @Test
    void deductPoints_无冻结记录_直接成功不记账() {
        when(freezeMapper.selectOne(any())).thenReturn(null);
        accountService.deductPoints(PointsDeductCommand.builder().userId(UID).bizNo("O9").build());
        verify(accountMapper, never()).deductFrozen(anyLong(), anyLong());
    }

    @Test
    void deductPoints_已实扣_幂等返回() {
        UserPointsFreeze freeze = new UserPointsFreeze();
        freeze.setStatus(1);
        when(freezeMapper.selectOne(any())).thenReturn(freeze);

        accountService.deductPoints(PointsDeductCommand.builder().userId(UID).bizNo("O1").build());
        verify(accountMapper, never()).deductFrozen(anyLong(), anyLong());
    }

    @Test
    void deductPoints_已释放_抛冲突() {
        UserPointsFreeze freeze = new UserPointsFreeze();
        freeze.setStatus(2);
        when(freezeMapper.selectOne(any())).thenReturn(freeze);

        assertThrows(BizException.class, () ->
                accountService.deductPoints(PointsDeductCommand.builder().userId(UID).bizNo("O1").build()));
    }

    @Test
    void releasePoints_冻结中_退回可用() {
        UserPointsFreeze freeze = new UserPointsFreeze();
        freeze.setId(9L);
        freeze.setUserId(UID);
        freeze.setOrderNo("O4");
        freeze.setPoints(200L);
        freeze.setStatus(0);
        freeze.setScene(PointsScene.CONSUME);
        when(freezeMapper.selectOne(any())).thenReturn(freeze);
        when(freezeMapper.update(any(), any())).thenReturn(1);
        when(accountMapper.releaseFrozen(UID, 200L)).thenReturn(1);
        when(accountMapper.selectByUserType(UID, AccountTypes.POINTS)).thenReturn(account(UID, 3, 1200L, 0L));

        accountService.releasePoints(PointsReleaseCommand.builder().userId(UID).bizNo("O4").build());

        verify(accountMapper).releaseFrozen(UID, 200L);
        verify(flowMapper).insert(any(UserAccountFlow.class));
    }

    @Test
    void releasePoints_无记录_幂等成功() {
        when(freezeMapper.selectOne(any())).thenReturn(null);
        accountService.releasePoints(PointsReleaseCommand.builder().userId(UID).bizNo("O9").build());
        verify(accountMapper, never()).releaseFrozen(anyLong(), anyLong());
    }

    @Test
    void releasePoints_已实扣_抛冲突() {
        UserPointsFreeze freeze = new UserPointsFreeze();
        freeze.setStatus(1);
        when(freezeMapper.selectOne(any())).thenReturn(freeze);
        assertThrows(BizException.class, () ->
                accountService.releasePoints(PointsReleaseCommand.builder().userId(UID).bizNo("O1").build()));
    }

    // ---------------- 退回 / 发放 ----------------

    @Test
    void refundPoints_正常退回_新批次365天并发事件() {
        when(flowMapper.selectByBizAndType("R1", PointsChangeType.REFUND)).thenReturn(null);
        when(accountMapper.credit(UID, AccountTypes.POINTS, 30L)).thenReturn(1);
        when(accountMapper.selectByUserType(UID, AccountTypes.POINTS)).thenReturn(account(UID, 3, 1030L, 0L));

        accountService.refundPoints(PointsRefundCommand.builder().userId(UID).bizNo("R1").points(30L).build());

        verify(grantMapper).insert(any(UserPointsGrant.class));
        verify(flowMapper).insert(any(UserAccountFlow.class));
        // R4-25：退回事件键 #ct5，与退款单其它阶段（如有）隔离
        verify(outboxPublisher).publish(any(), any(), any(), eq("R1#ct5"));
    }

    @Test
    void refundPoints_重复退款单_幂等跳过() {
        when(flowMapper.insert(any(UserAccountFlow.class))).thenThrow(new DuplicateKeyException("uk"));
        accountService.refundPoints(PointsRefundCommand.builder().userId(UID).bizNo("R1").points(30L).build());
        verify(accountMapper, never()).credit(anyLong(), anyInt(), anyLong());
    }

    @Test
    void grantPoints_消费场景_全额发放并入批次() {
        GrantPointsCommand cmd = GrantPointsCommand.builder()
                .userId(UID).bizNo("O10").points(55L).scene(PointsScene.CONSUME).build();
        when(flowMapper.selectByBizAndType("O10", PointsChangeType.EARN)).thenReturn(null);
        when(accountMapper.credit(UID, AccountTypes.POINTS, 55L)).thenReturn(1);
        when(accountMapper.selectByUserType(UID, AccountTypes.POINTS)).thenReturn(account(UID, 3, 1055L, 0L));

        long granted = accountService.grantPoints(cmd);

        assertEquals(55L, granted);
        verify(grantMapper).insert(any(UserPointsGrant.class));
        verify(dailyMapper, never()).addDaily(anyLong(), anyLong(), any(), anyInt(), anyLong());
    }

    @Test
    void grantPoints_签到已得40_今日上限裁剪到10() {
        GrantPointsCommand cmd = GrantPointsCommand.builder()
                .userId(UID).bizNo("SIGN-1").points(50L).scene(PointsScene.SIGN).build();
        when(flowMapper.selectByBizAndType("SIGN-1", PointsChangeType.EARN)).thenReturn(null);
        when(dailyMapper.sumDaily(eq(UID), any(), eq(PointsScene.SIGN))).thenReturn(40L);

        long granted = accountService.grantPoints(cmd);

        assertEquals(10L, granted);
        verify(accountMapper).credit(UID, AccountTypes.POINTS, 10L);
        verify(dailyMapper).addDaily(anyLong(), eq(UID), any(), eq(PointsScene.SIGN), eq(10L));
    }

    @Test
    void grantPoints_评价当日已满100_发放0不入账() {
        GrantPointsCommand cmd = GrantPointsCommand.builder()
                .userId(UID).bizNo("C1").points(30L).scene(PointsScene.COMMENT).build();
        when(flowMapper.selectByBizAndType("C1", PointsChangeType.EARN)).thenReturn(null);
        when(dailyMapper.sumDaily(eq(UID), any(), eq(PointsScene.COMMENT))).thenReturn(100L);

        assertEquals(0L, accountService.grantPoints(cmd));
        verify(accountMapper, never()).credit(anyLong(), anyInt(), anyLong());
        verify(flowMapper, never()).insert(any());
    }

    @Test
    void grantPoints_分享当日上限20_第二次被截断为0() {
        GrantPointsCommand cmd = GrantPointsCommand.builder()
                .userId(UID).bizNo("SH1").points(10L).scene(PointsScene.SHARE).build();
        when(flowMapper.selectByBizAndType("SH1", PointsChangeType.EARN)).thenReturn(null);
        when(dailyMapper.sumDaily(eq(UID), any(), eq(PointsScene.SHARE))).thenReturn(20L);

        assertEquals(0L, accountService.grantPoints(cmd));
    }

    @Test
    void grantPoints_重复bizNo_返回已发数量() {
        UserAccountFlow exist = new UserAccountFlow();
        exist.setAmount(55L);
        when(flowMapper.selectByBizAndType("O10", PointsChangeType.EARN)).thenReturn(exist);

        long granted = accountService.grantPoints(GrantPointsCommand.builder()
                .userId(UID).bizNo("O10").points(55L).scene(PointsScene.CONSUME).build());
        assertEquals(55L, granted);
        verify(accountMapper, never()).credit(anyLong(), anyInt(), anyLong());
    }

    // ---------------- 过期清零 ----------------

    @Test
    void expireDuePoints_到期批次清零并发过期事件() {
        UserPointsGrant b1 = new UserPointsGrant();
        b1.setId(101L);
        b1.setUserId(UID);
        b1.setPointsRemaining(30L);
        b1.setScene(PointsScene.SIGN);
        UserPointsGrant b2 = new UserPointsGrant();
        b2.setId(102L);
        b2.setUserId(2002L);
        b2.setPointsRemaining(70L);
        b2.setScene(PointsScene.CONSUME);
        when(grantMapper.selectList(any())).thenReturn(List.of(b1, b2), List.of());
        when(grantMapper.expireBatch(anyLong())).thenReturn(1);
        when(accountMapper.expirePoints(anyLong(), anyLong())).thenReturn(1);
        when(accountMapper.selectByUserType(anyLong(), eq(AccountTypes.POINTS)))
                .thenAnswer(inv -> account(inv.getArgument(0), 3, 0L, 0L));

        int count = accountService.expireDuePoints(LocalDateTime.now());

        assertEquals(2, count);
        verify(accountMapper).expirePoints(UID, 30L);
        verify(accountMapper).expirePoints(2002L, 70L);
        verify(flowMapper, times(2)).insert(any(UserAccountFlow.class));
        verify(outboxPublisher, times(2)).publish(any(), any(), any(), any());
    }

    @Test
    void expireDuePoints_同一用户跨分页_聚合事件键带分页游标不撞UK() {
        // R4-25 回归：同用户两页（BATCH_SIZE=200）聚合事件三元组相同，键必须再带 #p{maxId} 游标，
        // 否则第二条聚合 outbox 行必撞 uk_topic_tag_bizkey，整批过期清零回滚。
        java.util.List<UserPointsGrant> page1 = new java.util.ArrayList<>();
        for (long id = 1L; id <= 200L; id++) {
            UserPointsGrant g = new UserPointsGrant();
            g.setId(id);
            g.setUserId(UID);
            g.setPointsRemaining(10L);
            g.setScene(PointsScene.SIGN);
            page1.add(g);
        }
        UserPointsGrant tail = new UserPointsGrant();
        tail.setId(201L);
        tail.setUserId(UID);
        tail.setPointsRemaining(10L);
        tail.setScene(PointsScene.SIGN);
        when(grantMapper.selectList(any())).thenReturn(page1, List.of(tail), List.of());
        when(grantMapper.expireBatch(anyLong())).thenReturn(1);
        when(accountMapper.expirePoints(anyLong(), anyLong())).thenReturn(1);
        when(accountMapper.selectByUserType(eq(UID), eq(AccountTypes.POINTS)))
                .thenAnswer(inv -> account(UID, 3, 0L, 0L));

        LocalDateTime now = LocalDateTime.now();
        int count = accountService.expireDuePoints(now);

        assertEquals(201, count);
        org.mockito.ArgumentCaptor<String> keyCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(outboxPublisher, times(2)).publish(any(), any(), any(), keyCap.capture());
        String day = now.toLocalDate().toString();
        assertEquals(List.of("EXPIRE:" + UID + ":" + day + "#p0", "EXPIRE:" + UID + ":" + day + "#p200"),
                keyCap.getAllValues());
    }
}
