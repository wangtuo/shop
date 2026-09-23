package com.shop.user.account.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.api.user.dto.AmountCommand;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.PointsDeductCommand;
import com.shop.api.user.dto.PointsLockCommand;
import com.shop.api.user.dto.PointsRefundCommand;
import com.shop.api.user.dto.PointsReleaseCommand;
import com.shop.api.user.enums.AccountTypes;
import com.shop.api.user.enums.PointsChangeType;
import com.shop.api.user.enums.PointsScene;
import com.shop.api.user.event.PointsChangedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageQuery;
import com.shop.common.result.PageResult;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.user.account.dto.PointsAccountVO;
import com.shop.user.account.entity.UserAccount;
import com.shop.user.account.entity.UserAccountFlow;
import com.shop.user.account.entity.UserPointsFreeze;
import com.shop.user.account.entity.UserPointsGrant;
import com.shop.user.account.mapper.UserAccountFlowMapper;
import com.shop.user.account.mapper.UserAccountMapper;
import com.shop.user.account.mapper.UserPointsDailyMapper;
import com.shop.user.account.mapper.UserPointsFreezeMapper;
import com.shop.user.account.mapper.UserPointsGrantMapper;
import com.shop.user.account.service.AccountService;
import com.shop.user.member.PointsCalc;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户服务实现：行锁条件更新防透支；流水 (biz_no, change_type) 唯一键幂等；
 * 积分批次 365 天有效、FIFO 冲减；每日上限按 t_user_points_daily 汇总裁剪。
 */
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceImpl.class);

    /** 冻结记录状态 */
    private static final int FREEZE_LOCKED = 0;
    private static final int FREEZE_DEDUCTED = 1;
    private static final int FREEZE_RELEASED = 2;

    /** 资金账户流水方向：1 贷（入账） 2 借（扣减） */
    private static final int MONEY_CREDIT = 1;
    private static final int MONEY_DEBIT = 2;

    private static final int BATCH_SIZE = 200;

    private final UserAccountMapper accountMapper;
    private final UserAccountFlowMapper flowMapper;
    private final UserPointsFreezeMapper freezeMapper;
    private final UserPointsGrantMapper grantMapper;
    private final UserPointsDailyMapper dailyMapper;
    private final IdGenerator idGenerator;
    private final OutboxPublisher outboxPublisher;

    // ------------------------------------------------------------------
    // 余额 / 赠金
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initAccounts(Long userId) {
        getOrCreateAccount(userId, AccountTypes.BALANCE);
        getOrCreateAccount(userId, AccountTypes.GIFT);
        getOrCreateAccount(userId, AccountTypes.POINTS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void debitMoney(AmountCommand cmd, int accountType) {
        validateAmount(cmd, accountType);
        getOrCreateAccount(cmd.getUserId(), accountType);
        // P0-1 修复：先占 (biz_no, change_type) 幂等键，再动余额。
        // 并发重复请求在唯一键处互斥：占键失败方直接幂等返回，绝不可能出现「余额已扣/已加而无流水」。
        UserAccountFlow flow = occupyFlow(cmd.getUserId(), accountType, MONEY_DEBIT, cmd.getAmountFen(),
                cmd.getBizNo(), null, cmd.getRemark());
        if (flow == null) {
            return;
        }
        int rows = accountMapper.debit(cmd.getUserId(), accountType, cmd.getAmountFen());
        if (rows == 0) {
            // 条件更新失败 → 抛异常回滚，占位流水一并回滚，允许调用方重试
            throw new BizException(ErrorCode.PAY_ERROR,
                    accountType == AccountTypes.GIFT ? "赠金余额不足" : "余额不足");
        }
        finalizeFlow(flow, accountMapper.selectByUserType(cmd.getUserId(), accountType).getBalance());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void creditMoney(AmountCommand cmd) {
        validateAmount(cmd, AccountTypes.BALANCE);
        getOrCreateAccount(cmd.getUserId(), AccountTypes.BALANCE);
        // P0-1 修复：先占幂等键，后入账，杜绝不同 eventId 同 refundNo 的并发重复入账
        UserAccountFlow flow = occupyFlow(cmd.getUserId(), AccountTypes.BALANCE, MONEY_CREDIT,
                cmd.getAmountFen(), cmd.getBizNo(), null, cmd.getRemark());
        if (flow == null) {
            return;
        }
        accountMapper.credit(cmd.getUserId(), AccountTypes.BALANCE, cmd.getAmountFen());
        finalizeFlow(flow, accountMapper.selectByUserType(cmd.getUserId(), AccountTypes.BALANCE).getBalance());
    }

    private void validateAmount(AmountCommand cmd, int accountType) {
        if (cmd == null || cmd.getUserId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户ID不能为空");
        }
        if (cmd.getBizNo() == null || cmd.getBizNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "业务单号不能为空");
        }
        if (cmd.getAmountFen() == null || cmd.getAmountFen() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "变动金额必须为正数（分）");
        }
        if (accountType != AccountTypes.BALANCE && accountType != AccountTypes.GIFT) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法资金账户类型: " + accountType);
        }
    }

    // ------------------------------------------------------------------
    // 积分 TCC
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lockPoints(PointsLockCommand cmd) {
        if (cmd == null || cmd.getUserId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户ID不能为空");
        }
        if (cmd.getBizNo() == null || cmd.getBizNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "业务单号不能为空");
        }
        if (cmd.getPoints() == null || cmd.getPoints() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "冻结积分必须为正数");
        }
        if (cmd.getDeductFen() == null || cmd.getDeductFen() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "抵现金额必须为正数（分）");
        }
        // 100 积分 = 100 分 = 1 元，积分个与抵现分一一对应
        if (!cmd.getPoints().equals(cmd.getDeductFen())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "积分数量与抵现金额不一致（100积分=100分=1元）");
        }
        if (freezeMapper.selectOne(new LambdaQueryWrapper<UserPointsFreeze>()
                .eq(UserPointsFreeze::getOrderNo, cmd.getBizNo())) != null) {
            return;
        }
        int scene = cmd.getScene() == null ? PointsScene.CONSUME : cmd.getScene();
        // P0-1 修复：先占流水幂等键，后冻结积分；占位与余额变动同事务，失败一起回滚
        UserAccountFlow flow = occupyFlow(cmd.getUserId(), AccountTypes.POINTS, PointsChangeType.FREEZE,
                cmd.getPoints(), cmd.getBizNo(), scene, "下单冻结积分");
        if (flow == null) {
            return;
        }
        int rows = accountMapper.freezePoints(cmd.getUserId(), cmd.getPoints());
        if (rows == 0) {
            throw new BizException(ErrorCode.POINTS_NOT_ENOUGH);
        }
        UserPointsFreeze freeze = new UserPointsFreeze();
        freeze.setId(idGenerator.nextId());
        freeze.setUserId(cmd.getUserId());
        freeze.setOrderNo(cmd.getBizNo());
        freeze.setPoints(cmd.getPoints());
        freeze.setDeductFen(cmd.getDeductFen());
        freeze.setScene(scene);
        freeze.setStatus(FREEZE_LOCKED);
        // 流水幂等键已先占，冻结记录 UK 冲突属不应发生的异常：直接外抛回滚，禁止吞掉
        freezeMapper.insert(freeze);
        long balanceAfter = accountMapper.selectByUserType(cmd.getUserId(), AccountTypes.POINTS).getBalance();
        finalizeFlow(flow, balanceAfter);
        publishPointsEvent(cmd.getUserId(), PointsChangeType.FREEZE, cmd.getPoints(),
                balanceAfter, scene, cmd.getBizNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductPoints(PointsDeductCommand cmd) {
        if (cmd == null || cmd.getUserId() == null || cmd.getBizNo() == null || cmd.getBizNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户ID与业务单号不能为空");
        }
        UserPointsFreeze freeze = freezeMapper.selectOne(new LambdaQueryWrapper<UserPointsFreeze>()
                .eq(UserPointsFreeze::getOrderNo, cmd.getBizNo()));
        // 未冻结过积分（下单未用积分）：直接成功
        if (freeze == null) {
            return;
        }
        if (freeze.getStatus() == FREEZE_DEDUCTED) {
            return;
        }
        if (freeze.getStatus() == FREEZE_RELEASED) {
            throw new BizException(ErrorCode.CONFLICT, "冻结积分已释放，不能实扣: " + cmd.getBizNo());
        }
        if (flowMapper.selectByBizAndType(cmd.getBizNo(), PointsChangeType.CONSUME) != null) {
            return;
        }
        int stateRows = freezeMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update
                .LambdaUpdateWrapper<UserPointsFreeze>()
                .eq(UserPointsFreeze::getId, freeze.getId())
                .eq(UserPointsFreeze::getStatus, FREEZE_LOCKED)
                .set(UserPointsFreeze::getStatus, FREEZE_DEDUCTED));
        if (stateRows == 0) {
            return;
        }
        int rows = accountMapper.deductFrozen(cmd.getUserId(), freeze.getPoints());
        if (rows == 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "冻结积分核销异常: " + cmd.getBizNo());
        }
        consumeFifoBatches(cmd.getUserId(), freeze.getPoints());
        long balanceAfter = accountMapper.selectByUserType(cmd.getUserId(), AccountTypes.POINTS).getBalance();
        // 冻结记录状态 CAS 已保证唯一赢家；此处流水 UK 若冲突属异常，必须回滚而非吞掉（P0-1）
        insertFlowStrict(cmd.getUserId(), AccountTypes.POINTS, PointsChangeType.CONSUME, freeze.getPoints(),
                balanceAfter, cmd.getBizNo(), freeze.getScene(), "支付成功实扣积分");
        publishPointsEvent(cmd.getUserId(), PointsChangeType.CONSUME, freeze.getPoints(),
                balanceAfter, freeze.getScene(), cmd.getBizNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releasePoints(PointsReleaseCommand cmd) {
        if (cmd == null || cmd.getUserId() == null || cmd.getBizNo() == null || cmd.getBizNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户ID与业务单号不能为空");
        }
        UserPointsFreeze freeze = freezeMapper.selectOne(new LambdaQueryWrapper<UserPointsFreeze>()
                .eq(UserPointsFreeze::getOrderNo, cmd.getBizNo()));
        if (freeze == null || freeze.getStatus() == FREEZE_RELEASED) {
            return;
        }
        if (freeze.getStatus() == FREEZE_DEDUCTED) {
            throw new BizException(ErrorCode.CONFLICT, "冻结积分已实扣，不能释放: " + cmd.getBizNo());
        }
        int stateRows = freezeMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update
                .LambdaUpdateWrapper<UserPointsFreeze>()
                .eq(UserPointsFreeze::getId, freeze.getId())
                .eq(UserPointsFreeze::getStatus, FREEZE_LOCKED)
                .set(UserPointsFreeze::getStatus, FREEZE_RELEASED));
        if (stateRows == 0) {
            return;
        }
        int rows = accountMapper.releaseFrozen(cmd.getUserId(), freeze.getPoints());
        if (rows == 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "冻结积分释放异常: " + cmd.getBizNo());
        }
        long balanceAfter = accountMapper.selectByUserType(cmd.getUserId(), AccountTypes.POINTS).getBalance();
        // 冻结记录状态 CAS 已保证唯一赢家；流水 UK 冲突直接回滚（P0-1）
        insertFlowStrict(cmd.getUserId(), AccountTypes.POINTS, PointsChangeType.RELEASE, freeze.getPoints(),
                balanceAfter, cmd.getBizNo(), freeze.getScene(), "订单取消释放冻结积分");
        publishPointsEvent(cmd.getUserId(), PointsChangeType.RELEASE, freeze.getPoints(),
                balanceAfter, freeze.getScene(), cmd.getBizNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundPoints(PointsRefundCommand cmd) {
        if (cmd == null || cmd.getUserId() == null || cmd.getBizNo() == null || cmd.getBizNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户ID与退款单号不能为空");
        }
        if (cmd.getPoints() == null || cmd.getPoints() <= 0) {
            return;
        }
        getOrCreateAccount(cmd.getUserId(), AccountTypes.POINTS);
        // P0-1 修复：先占幂等键（不同 eventId 的重复 REFUND_SUCCESS 也在此被挡住），再退积分
        UserAccountFlow flow = occupyFlow(cmd.getUserId(), AccountTypes.POINTS, PointsChangeType.REFUND,
                cmd.getPoints(), cmd.getBizNo(), PointsScene.CONSUME, "退款退回积分");
        if (flow == null) {
            return;
        }
        accountMapper.credit(cmd.getUserId(), AccountTypes.POINTS, cmd.getPoints());
        // 退回积分生成独立批次，自退款之日起重新计 365 天有效期
        LocalDateTime now = LocalDateTime.now();
        UserPointsGrant batch = new UserPointsGrant();
        batch.setId(idGenerator.nextId());
        batch.setUserId(cmd.getUserId());
        batch.setBizNo(cmd.getBizNo());
        batch.setScene(PointsScene.CONSUME);
        batch.setPointsTotal(cmd.getPoints());
        batch.setPointsRemaining(cmd.getPoints());
        batch.setGrantTime(now);
        batch.setExpireTime(PointsCalc.expireTime(now));
        batch.setStatus(0);
        // 流水键已先占，批次 UK 冲突属异常：外抛回滚，禁止余额已加而吞掉批次冲突
        grantMapper.insert(batch);
        long balanceAfter = accountMapper.selectByUserType(cmd.getUserId(), AccountTypes.POINTS).getBalance();
        finalizeFlow(flow, balanceAfter);
        publishPointsEvent(cmd.getUserId(), PointsChangeType.REFUND, cmd.getPoints(),
                balanceAfter, PointsScene.CONSUME, cmd.getBizNo());
    }

    // ------------------------------------------------------------------
    // 积分发放（每日上限 + 365 天批次）
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long grantPoints(GrantPointsCommand cmd) {
        if (cmd == null || cmd.getUserId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户ID不能为空");
        }
        if (cmd.getBizNo() == null || cmd.getBizNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "业务单号不能为空");
        }
        if (cmd.getPoints() == null || cmd.getPoints() < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "发放积分不能为负");
        }
        if (cmd.getScene() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "积分场景不能为空");
        }
        UserAccountFlow exist = flowMapper.selectByBizAndType(cmd.getBizNo(), PointsChangeType.EARN);
        if (exist != null) {
            return exist.getAmount();
        }
        long requested = cmd.getPoints();
        if (requested == 0) {
            return 0L;
        }
        long dailyCap = dailyCapOf(cmd.getScene());
        long actual = requested;
        if (dailyCap > 0) {
            LocalDate today = LocalDate.now();
            Long earned = dailyMapper.sumDaily(cmd.getUserId(), today, cmd.getScene());
            actual = PointsCalc.clampByDailyCap(requested, earned == null ? 0L : earned, dailyCap);
        }
        if (actual <= 0) {
            log.info("当日场景积分已达上限，本次发放0 userId={} scene={} bizNo={}",
                    cmd.getUserId(), cmd.getScene(), cmd.getBizNo());
            return 0L;
        }
        getOrCreateAccount(cmd.getUserId(), AccountTypes.POINTS);
        // P0-1 修复：先占幂等键再发积分。顺序重试走上面的 select 返回原发放额；
        // 并发同 bizNo（含不同 eventId）时唯一键互斥，负方返回 0，绝不重复发放
        UserAccountFlow flow = occupyFlow(cmd.getUserId(), AccountTypes.POINTS, PointsChangeType.EARN,
                actual, cmd.getBizNo(), cmd.getScene(), "积分发放");
        if (flow == null) {
            return 0L;
        }
        accountMapper.credit(cmd.getUserId(), AccountTypes.POINTS, actual);

        LocalDateTime now = LocalDateTime.now();
        UserPointsGrant batch = new UserPointsGrant();
        batch.setId(idGenerator.nextId());
        batch.setUserId(cmd.getUserId());
        batch.setBizNo(cmd.getBizNo());
        batch.setScene(cmd.getScene());
        batch.setPointsTotal(actual);
        batch.setPointsRemaining(actual);
        batch.setGrantTime(now);
        batch.setExpireTime(PointsCalc.expireTime(now));
        batch.setStatus(0);
        // 流水键已先占，批次 UK 冲突属异常：外抛回滚整笔发放（P0-1）
        grantMapper.insert(batch);
        if (dailyCap > 0) {
            dailyMapper.addDaily(idGenerator.nextId(), cmd.getUserId(), LocalDate.now(), cmd.getScene(), actual);
        }
        long balanceAfter = accountMapper.selectByUserType(cmd.getUserId(), AccountTypes.POINTS).getBalance();
        finalizeFlow(flow, balanceAfter);
        publishPointsEvent(cmd.getUserId(), PointsChangeType.EARN, actual,
                balanceAfter, cmd.getScene(), cmd.getBizNo());
        return actual;
    }

    /** 每日获取上限：签到 50、评价 100、分享 20；消费/晒单/补偿无上限 */
    private long dailyCapOf(int scene) {
        if (scene == PointsScene.SIGN) {
            return PointsCalc.SIGN_DAILY_CAP;
        }
        if (scene == PointsScene.COMMENT) {
            return PointsCalc.COMMENT_DAILY_CAP;
        }
        if (scene == PointsScene.SHARE) {
            return PointsCalc.SHARE_DAILY_CAP;
        }
        return 0L;
    }

    /** FIFO 冲减最早到期的积分批次 */
    private void consumeFifoBatches(Long userId, long points) {
        List<UserPointsGrant> batches = grantMapper.selectFifoAvailable(userId);
        long remaining = points;
        for (UserPointsGrant batch : batches) {
            if (remaining <= 0) {
                break;
            }
            long take = Math.min(remaining, batch.getPointsRemaining());
            if (take <= 0) {
                continue;
            }
            int rows = grantMapper.consumeRemaining(batch.getId(), take);
            if (rows == 0) {
                continue;
            }
            remaining -= take;
        }
        if (remaining > 0) {
            throw new BizException(ErrorCode.POINTS_NOT_ENOUGH, "可用积分批次余额不足以核销");
        }
    }

    // ------------------------------------------------------------------
    // 过期清零（每日定时任务）
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int expireDuePoints(LocalDateTime now) {
        int expiredBatches = 0;
        long maxId = 0L;
        while (true) {
            List<UserPointsGrant> page = grantMapper.selectList(new LambdaQueryWrapper<UserPointsGrant>()
                    .gt(UserPointsGrant::getId, maxId)
                    .eq(UserPointsGrant::getStatus, 0)
                    .le(UserPointsGrant::getExpireTime, now)
                    .orderByAsc(UserPointsGrant::getId)
                    .last("LIMIT " + BATCH_SIZE));
            if (page.isEmpty()) {
                break;
            }
            Map<Long, long[]> userTotals = new HashMap<>();
            for (UserPointsGrant batch : page) {
                int rows = grantMapper.expireBatch(batch.getId());
                if (rows == 0) {
                    continue;
                }
                expiredBatches++;
                long remain = batch.getPointsRemaining();
                if (remain <= 0) {
                    continue;
                }
                int accountRows = accountMapper.expirePoints(batch.getUserId(), remain);
                if (accountRows == 0) {
                    throw new BizException(ErrorCode.SYSTEM_ERROR,
                            "过期积分扣减可用余额异常 userId=" + batch.getUserId());
                }
                userTotals.computeIfAbsent(batch.getUserId(), k -> new long[]{0L})[0] += remain;
                long balanceAfter = accountMapper.selectByUserType(batch.getUserId(), AccountTypes.POINTS).getBalance();
                // expireBatch 条件更新已保证唯一赢家；流水 UK 冲突直接回滚（P0-1）
                insertFlowStrict(batch.getUserId(), AccountTypes.POINTS, PointsChangeType.EXPIRE, remain,
                        balanceAfter, "EXPIRE:" + batch.getId(), batch.getScene(), "积分365天到期清零");
            }
            // R4-25：同一用户跨分页的过期聚合必须再带分页游标维度，否则同 (userId,date)
            // 两条聚合事件以相同 outbox 三元组第二次登记必撞 uk_topic_tag_bizkey 回滚整批清零
            final long pageCursor = maxId;
            userTotals.forEach((userId, total) -> publishPointsEvent(userId, PointsChangeType.EXPIRE,
                    total[0], accountMapper.selectByUserType(userId, AccountTypes.POINTS).getBalance(),
                    PointsScene.COMPENSATE, "EXPIRE:" + userId + ":" + now.toLocalDate(),
                    "EXPIRE:" + userId + ":" + now.toLocalDate() + "#p" + pageCursor));
            maxId = page.get(page.size() - 1).getId();
            if (page.size() < BATCH_SIZE) {
                break;
            }
        }
        return expiredBatches;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public PointsAccountVO getPointsAccount(Long userId) {
        UserAccount account = getOrCreateAccount(userId, AccountTypes.POINTS);
        return PointsAccountVO.builder()
                .balance(account.getBalance())
                .frozen(account.getFrozen())
                .build();
    }

    @Override
    public PageResult<UserAccountFlow> pageFlows(Long userId, int accountType, PageQuery query) {
        if (accountType < AccountTypes.BALANCE || accountType > AccountTypes.POINTS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法账户类型: " + accountType);
        }
        Page<UserAccountFlow> page = new Page<>(query.safePageNum(), query.safePageSize());
        Page<UserAccountFlow> result = flowMapper.selectPage(page, new LambdaQueryWrapper<UserAccountFlow>()
                .eq(UserAccountFlow::getUserId, userId)
                .eq(UserAccountFlow::getAccountType, accountType)
                .orderByDesc(UserAccountFlow::getId));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    // ------------------------------------------------------------------
    // 公共辅助
    // ------------------------------------------------------------------

    private UserAccount getOrCreateAccount(Long userId, int accountType) {
        UserAccount account = accountMapper.selectByUserType(userId, accountType);
        if (account != null) {
            return account;
        }
        account = new UserAccount();
        account.setId(idGenerator.nextId());
        account.setUserId(userId);
        account.setAccountType(accountType);
        account.setBalance(0L);
        account.setFrozen(0L);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            account = accountMapper.selectByUserType(userId, accountType);
        }
        return account;
    }

    /**
     * 先占幂等键：在动余额之前插入 status=0（处理中）的流水占位行。
     * 唯一键 (biz_no, change_type) 冲突（顺序重试或并发重复投递，即使 MQ eventId 不同）时返回 null，
     * 调用方必须直接幂等返回。占位与后续余额更新在同一事务内，任一步失败整体回滚。
     */
    private UserAccountFlow occupyFlow(Long userId, int accountType, int changeType, long amount,
                                       String bizNo, Integer scene, String remark) {
        UserAccountFlow flow = new UserAccountFlow();
        flow.setId(idGenerator.nextId());
        flow.setUserId(userId);
        flow.setAccountType(accountType);
        flow.setChangeType(changeType);
        flow.setAmount(amount);
        flow.setBalanceAfter(0L);
        flow.setBizNo(bizNo);
        flow.setScene(scene);
        flow.setRemark(remark);
        flow.setStatus(0);
        try {
            flowMapper.insert(flow);
            return flow;
        } catch (DuplicateKeyException e) {
            log.info("账户流水幂等键已占用，跳过重复记账 bizNo={} changeType={}", bizNo, changeType);
            return null;
        }
    }

    /** 余额更新成功后回填变动后余额并置为成功态。 */
    private void finalizeFlow(UserAccountFlow flow, long balanceAfter) {
        flow.setBalanceAfter(balanceAfter);
        flow.setStatus(1);
        flowMapper.updateById(flow);
    }

    /**
     * 严格流水落库：调用方已用状态 CAS / 条件更新取得唯一赢家身份。
     * 此处若唯一键冲突说明出现预期外的并发，必须外抛回滚事务，禁止吞掉造成账实不符（P0-1）。
     */
    private void insertFlowStrict(Long userId, int accountType, int changeType, long amount,
                                  long balanceAfter, String bizNo, Integer scene, String remark) {
        UserAccountFlow flow = new UserAccountFlow();
        flow.setId(idGenerator.nextId());
        flow.setUserId(userId);
        flow.setAccountType(accountType);
        flow.setChangeType(changeType);
        flow.setAmount(amount);
        flow.setBalanceAfter(balanceAfter);
        flow.setBizNo(bizNo);
        flow.setScene(scene);
        flow.setRemark(remark);
        flow.setStatus(1);
        flowMapper.insert(flow);
    }

    private void publishPointsEvent(Long userId, int changeType, long points,
                                    long balanceAfter, int scene, String bizNo) {
        publishPointsEvent(userId, changeType, points, balanceAfter, scene, bizNo, null);
    }

    /**
     * 登记积分变动 outbox 事件。
     *
     * <p>R4-25：outbox 唯一键是 {@code (topic, tag, biz_key)} 且行投递后永不删除，而积分流水
     * 唯一键是 {@code (biz_no, change_type)}——同一业务单号会经历多个变动阶段：下单 FREEZE、
     * 支付成功 CONSUME、取消 RELEASE（抽奖还有 freeze→deduct→release 同 bizNo）。若 outbox
     * bizKey 只用裸 bizNo，第二阶段事务的 INSERT 必抛 DuplicateKeyException 回滚整笔业务
     * （用积分订单的支付实扣必失败、取消释放失败致积分永久冻结）。故事务性 outbox 键统一追加
     * {@code #ct}{changeType}；消息体 {@code bizNo} 仍为裸业务单号，消费侧按 (bizNo,changeType)
     * 幂等的口径不变。</p>
     *
     * @param outboxBizKey 显式 outbox 键（如过期跨分页聚合）；null 时按 bizNo#ct{changeType} 派生
     */
    private void publishPointsEvent(Long userId, int changeType, long points,
                                    long balanceAfter, int scene, String bizNo, String outboxBizKey) {
        PointsChangedEvent event = PointsChangedEvent.builder()
                .userId(userId)
                .changeType(changeType)
                .points(points)
                .balanceAfter(balanceAfter)
                .scene(scene)
                .bizNo(bizNo)
                .build();
        // P1-1：积分事件与余额/流水同事务提交（occupy→条件变更→finalize 时序不变），
        // 由 outbox relay 至少一次投递，消费端按 (bizNo,changeType) 幂等；bizNo 沿用原业务单号。
        String dedupeKey = outboxBizKey != null ? outboxBizKey : bizNo + "#ct" + changeType;
        outboxPublisher.publish(MqTopics.POINTS_CHANGED, null, event, dedupeKey);
    }
}
