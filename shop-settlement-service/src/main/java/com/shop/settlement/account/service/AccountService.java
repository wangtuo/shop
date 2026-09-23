package com.shop.settlement.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.settlement.account.entity.SettAccount;
import com.shop.settlement.account.entity.SettAccountFlow;
import com.shop.settlement.account.mapper.AccountFlowMapper;
import com.shop.settlement.account.mapper.AccountMapper;
import com.shop.settlement.support.SettleNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资金账户记账服务：条件更新防透支 + (biz_no, change_type) 流水幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountMapper accountMapper;
    private final AccountFlowMapper flowMapper;
    private final SettleNoGenerator noGenerator;

    /** 获取账户，不存在则初始化（余额 0）。 */
    @Transactional
    public SettAccount getOrCreate(long ownerId, int roleType) {
        SettAccount account = accountMapper.selectOne(new LambdaQueryWrapper<SettAccount>()
                .eq(SettAccount::getOwnerId, ownerId)
                .eq(SettAccount::getRoleType, roleType));
        if (account != null) {
            return account;
        }
        account = new SettAccount();
        account.setOwnerId(ownerId);
        account.setRoleType(roleType);
        account.setAvailableFen(0L);
        account.setFrozenFen(0L);
        account.setPendingSettleFen(0L);
        try {
            accountMapper.insert(account);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            account = accountMapper.selectOne(new LambdaQueryWrapper<SettAccount>()
                    .eq(SettAccount::getOwnerId, ownerId)
                    .eq(SettAccount::getRoleType, roleType));
        }
        return account;
    }

    /** 流水是否已登记（幂等判断）。 */
    public boolean flowExists(String bizNo, int changeType) {
        return flowMapper.selectCount(new LambdaQueryWrapper<SettAccountFlow>()
                .eq(SettAccountFlow::getBizNo, bizNo)
                .eq(SettAccountFlow::getChangeType, changeType)) > 0;
    }

    /** 入待结算（清算/日终批），biz+type 幂等。 */
    public void creditPending(long ownerId, int roleType, String bizNo, int changeType,
                              long amount, String remark) {
        if (amount == 0 || flowExists(bizNo, changeType)) {
            return;
        }
        ensureAccountExists(ownerId, roleType);
        int rows = accountMapper.creditPending(ownerId, roleType, amount);
        if (rows != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "待结算入账失败");
        }
        writeFlow(ownerId, roleType, bizNo, changeType, 0L, 0L, amount, remark);
    }

    /** 待结算转可提现（日终批），biz+type 幂等。 */
    public void pendingToAvailable(long ownerId, int roleType, String bizNo, int changeType,
                                   long amount, String remark) {
        if (amount == 0 || flowExists(bizNo, changeType)) {
            return;
        }
        int rows = accountMapper.pendingToAvailable(ownerId, roleType, amount);
        if (rows != 1) {
            throw new BizException(ErrorCode.SETTLE_AMOUNT_ERROR, "待结算余额不足，转可提现失败: " + bizNo);
        }
        writeFlow(ownerId, roleType, bizNo, changeType, amount, 0L, -amount, remark);
    }

    /** 可用余额入账（平台费用收入/补贴冲正退回等），biz+type 幂等。 */
    public void creditAvailable(long ownerId, int roleType, String bizNo, int changeType,
                                long amount, String remark) {
        if (amount == 0 || flowExists(bizNo, changeType)) {
            return;
        }
        ensureAccountExists(ownerId, roleType);
        int rows = accountMapper.creditAvailable(ownerId, roleType, amount);
        if (rows != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "可用余额入账失败");
        }
        writeFlow(ownerId, roleType, bizNo, changeType, amount, 0L, 0L, remark);
    }

    /** 可用余额出账（营销补贴出资等系统账户），biz+type 幂等。 */
    public void debitAvailable(long ownerId, int roleType, String bizNo, int changeType,
                               long amount, String remark) {
        if (amount == 0 || flowExists(bizNo, changeType)) {
            return;
        }
        ensureAccountExists(ownerId, roleType);
        int rows = accountMapper.debitAvailable(ownerId, roleType, amount);
        if (rows != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "可用余额出账失败");
        }
        writeFlow(ownerId, roleType, bizNo, changeType, -amount, 0L, 0L, remark);
    }

    /**
     * 原子部分扣减待结算（退款瀑布第一档，P1-12）。
     * 固定序列：{@code SELECT ... FOR UPDATE} 锁读当前余额 → 单条 LEAST 原子 SQL 扣减
     * min(need, pending) → 写 (bizNo, changeType) 幂等流水。
     * 不再「先普通 SELECT 再按读到的值条件更新」：行锁 + LEAST 使并发两笔退款对同一商户
     * 串行化，实际扣减值精确，未扣够的部分由调用方继续走可提现、保证金档。
     *
     * @return 实际扣减金额（0 ~ need；待结算为 0 / 流水已存在时返回 0）
     */
    public long debitPendingPartial(long ownerId, int roleType, String bizNo, int changeType,
                                    long need, String remark) {
        if (need <= 0 || flowExists(bizNo, changeType)) {
            return 0L;
        }
        SettAccount before = accountMapper.selectForUpdate(ownerId, roleType);
        if (before == null) {
            return 0L;
        }
        long actual = Math.min(need, Math.max(nz(before.getPendingSettleFen()), 0L));
        if (actual <= 0) {
            return 0L;
        }
        int rows = accountMapper.debitPendingPartial(ownerId, roleType, need);
        if (rows != 1) {
            // 锁内余额 > 0 却扣减失败，只能是并发/数据异常，抛错回滚由 MQ 重试
            throw new BizException(ErrorCode.SYSTEM_ERROR, "待结算原子扣减失败: " + bizNo);
        }
        writeFlow(ownerId, roleType, bizNo, changeType, 0L, 0L, -actual, remark);
        return actual;
    }

    /**
     * 原子部分扣减可提现余额（退款瀑布第二档，P1-10）。语义同
     * {@link #debitPendingPartial}，扣 min(need, available)。
     *
     * @return 实际扣减金额（0 ~ need）
     */
    public long debitAvailablePartial(long ownerId, int roleType, String bizNo, int changeType,
                                      long need, String remark) {
        if (need <= 0 || flowExists(bizNo, changeType)) {
            return 0L;
        }
        SettAccount before = accountMapper.selectForUpdate(ownerId, roleType);
        if (before == null) {
            return 0L;
        }
        long actual = Math.min(need, Math.max(nz(before.getAvailableFen()), 0L));
        if (actual <= 0) {
            return 0L;
        }
        int rows = accountMapper.debitAvailablePartial(ownerId, roleType, need);
        if (rows != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "可提现余额原子扣减失败: " + bizNo);
        }
        writeFlow(ownerId, roleType, bizNo, changeType, -actual, 0L, 0L, remark);
        return actual;
    }

    /** 提现冻结：可用→冻结，余额不足抛 WITHDRAW_LIMIT，biz+type 幂等。 */
    public void freeze(long ownerId, int roleType, String bizNo, long amount, String remark) {
        if (flowExists(bizNo, com.shop.settlement.enums.FlowChangeTypes.WITHDRAW_FREEZE)) {
            return;
        }
        int rows = accountMapper.freeze(ownerId, roleType, amount);
        if (rows != 1) {
            throw new BizException(ErrorCode.WITHDRAW_LIMIT, "可提现余额不足");
        }
        writeFlow(ownerId, roleType, bizNo, com.shop.settlement.enums.FlowChangeTypes.WITHDRAW_FREEZE,
                -amount, amount, 0L, remark);
    }

    /** 提现成功：冻结出款。 */
    public void unfreezeOut(long ownerId, int roleType, String bizNo, long amount, String remark) {
        if (amount == 0 || flowExists(bizNo, com.shop.settlement.enums.FlowChangeTypes.WITHDRAW_REMIT)) {
            return;
        }
        int rows = accountMapper.unfreezeOut(ownerId, roleType, amount);
        if (rows != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "提现出款失败，冻结余额不足");
        }
        writeFlow(ownerId, roleType, bizNo, com.shop.settlement.enums.FlowChangeTypes.WITHDRAW_REMIT,
                0L, -amount, 0L, remark);
    }

    /** 提现失败/拒绝：冻结退回可用。 */
    public void unfreezeBack(long ownerId, int roleType, String bizNo, int changeType,
                             long amount, String remark) {
        if (amount == 0 || flowExists(bizNo, changeType)) {
            return;
        }
        int rows = accountMapper.unfreezeBack(ownerId, roleType, amount);
        if (rows != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "提现退回失败，冻结余额不足");
        }
        writeFlow(ownerId, roleType, bizNo, changeType, amount, -amount, 0L, remark);
    }

    /** 仅写一条不移动三类余额的留痕流水（保证金赔付在账户侧的记录）。 */
    public void writeZeroFlow(long ownerId, int roleType, String bizNo, int changeType, String remark) {
        if (flowExists(bizNo, changeType)) {
            return;
        }
        ensureAccountExists(ownerId, roleType);
        writeFlow(ownerId, roleType, bizNo, changeType, 0L, 0L, 0L, remark);
    }

    private void ensureAccountExists(long ownerId, int roleType) {
        Long count = accountMapper.selectCount(new LambdaQueryWrapper<SettAccount>()
                .eq(SettAccount::getOwnerId, ownerId)
                .eq(SettAccount::getRoleType, roleType));
        if (count == null || count == 0) {
            getOrCreate(ownerId, roleType);
        }
    }

    private void writeFlow(long ownerId, int roleType, String bizNo, int changeType,
                           long availableChange, long frozenChange, long pendingChange, String remark) {
        SettAccount account = accountMapper.selectOne(new LambdaQueryWrapper<SettAccount>()
                .eq(SettAccount::getOwnerId, ownerId)
                .eq(SettAccount::getRoleType, roleType));
        SettAccountFlow flow = new SettAccountFlow();
        flow.setFlowNo(noGenerator.nextFlowNo());
        flow.setOwnerId(ownerId);
        flow.setRoleType(roleType);
        flow.setBizNo(bizNo);
        flow.setChangeType(changeType);
        flow.setAvailableChange(availableChange);
        flow.setFrozenChange(frozenChange);
        flow.setPendingChange(pendingChange);
        flow.setAvailableAfter(account == null ? 0L : account.getAvailableFen());
        flow.setFrozenAfter(account == null ? 0L : account.getFrozenFen());
        flow.setPendingAfter(account == null ? 0L : account.getPendingSettleFen());
        flow.setRemark(remark == null ? "" : remark);
        flowMapper.insert(flow);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
