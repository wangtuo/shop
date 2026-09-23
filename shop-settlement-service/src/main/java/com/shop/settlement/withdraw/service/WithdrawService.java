package com.shop.settlement.withdraw.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.api.settlement.enums.WithdrawStatuses;
import com.shop.api.settlement.event.WithdrawResultEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.framework.idempotent.Idempotent;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.enums.AutoFrequencies;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.enums.WithdrawChannels;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.support.DataCipher;
import com.shop.settlement.support.SettleNoGenerator;
import com.shop.settlement.withdraw.dto.ApplyWithdrawRequest;
import com.shop.settlement.withdraw.dto.AutoWithdrawConfigRequest;
import com.shop.settlement.withdraw.entity.SettWithdraw;
import com.shop.settlement.withdraw.entity.SettWithdrawAutoConfig;
import com.shop.settlement.withdraw.entity.SettWithdrawDailyCount;
import com.shop.settlement.withdraw.mapper.WithdrawAutoConfigMapper;
import com.shop.settlement.withdraw.mapper.WithdrawDailyCountMapper;
import com.shop.settlement.withdraw.mapper.WithdrawMapper;
import com.shop.settlement.withdraw.vo.AutoWithdrawConfigVO;
import com.shop.settlement.withdraw.vo.WithdrawVO;
import com.shop.settlement.engine.WithdrawCalculator;
import com.shop.settlement.remit.RemitQueryRequest;
import com.shop.settlement.remit.RemitQueryResult;
import com.shop.settlement.remit.RemitRequest;
import com.shop.settlement.remit.RemitResult;
import com.shop.settlement.remit.RemitRouter;
import com.shop.settlement.remit.RemitStatuses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 提现服务（design 7.4）：
 * 最低 100 元、单日 50 万上限、每月前 3 笔免费其后 0.1%（最低 2 元）；
 * 状态 10 申请→20 审核→30 成功（ShedLock 批次模拟渠道 T+1 打款）/40 失败（余额退回）/50 拒绝；
 * 保证金低于应缴 50% 禁止提现；支持银行卡/支付宝与每日/每周自动提现；终态发 WITHDRAW_RESULT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    /** 自动/人工批次每轮扫描条数 */
    public static final int BATCH_LIMIT = 500;

    private final WithdrawMapper withdrawMapper;
    private final WithdrawDailyCountMapper countMapper;
    private final WithdrawAutoConfigMapper autoConfigMapper;
    private final MerchantService merchantService;
    private final DepositService depositService;
    private final AccountService accountService;
    private final WithdrawCalculator calculator;
    private final DistributedLockTemplate lockTemplate;
    private final SettleNoGenerator noGenerator;
    // P1-1：WITHDRAW_RESULT 在 remitBatch/markFailed/refuse 事务内登记 outbox，与状态/余额变更同提交
    private final OutboxPublisher outboxPublisher;
    private final DataCipher dataCipher;
    // B10：真实代发渠道
    private final RemitRouter remitRouter;

    /** 自注入：批次编排（无事务）调用短事务方法必须经过代理 */
    @Lazy
    @Autowired
    private WithdrawService self;

    /**
     * 商户发起提现申请（冻结可提现余额）。
     *
     * <p>M-3 幂等键：服务端身份 merchantId + 客户端 clientToken；clientToken 缺省时用服务端预生成的
     * withdrawNo（controller 写入 request.withdrawNo，忽略任何 body 内的 withdrawNo）。
     */
    @Idempotent(prefix = "settle:withdraw",
            key = "#merchantId + ':' + (#request.clientToken != null ? #request.clientToken : #request.withdrawNo)")
    @Transactional
    public SettWithdraw apply(long merchantId, ApplyWithdrawRequest request, boolean autoWithdraw) {
        if (!WithdrawChannels.valid(request.getChannel())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "提现渠道仅支持银行卡(1)/支付宝(2)");
        }
        SettMerchant merchant = merchantService.requireActiveMerchant(merchantId);
        return lockTemplate.execute("lock:settle:withdraw:" + merchantId, () ->
                doApply(merchant, request, autoWithdraw));
    }

    private SettWithdraw doApply(SettMerchant merchant, ApplyWithdrawRequest req, boolean autoWithdraw) {
        long merchantId = merchant.getId();
        long amount = req.getAmountFen();
        LocalDate today = LocalDate.now();
        String month = today.format(MONTH_FMT);

        SettWithdrawDailyCount todayCount = countMapper.selectOne(new LambdaQueryWrapper<SettWithdrawDailyCount>()
                .eq(SettWithdrawDailyCount::getMerchantId, merchantId)
                .eq(SettWithdrawDailyCount::getStatDate, today));
        long dailyAlready = todayCount == null || todayCount.getDailyAmountFen() == null
                ? 0L : todayCount.getDailyAmountFen();
        int monthApplied = countMapper.sumMonthApplyCount(merchantId, month);

        // 金额门槛 + 单日 50 万上限
        calculator.validateAmount(amount, dailyAlready);
        // 每月前 3 笔免费，其后 0.1% 最低 2 元
        long fee = calculator.fee(amount, monthApplied);
        boolean free = calculator.freeOfCharge(monthApplied);

        // 保证金低于应缴 50% 限制提现
        if (depositService.belowAlertThreshold(merchant)) {
            throw new BizException(ErrorCode.WITHDRAW_LIMIT,
                    "保证金余额低于应缴额50%，已限制提现，请先补足保证金");
        }

        // 冻结可提现余额（待结算/冻结不可提），条件更新防透支
        String withdrawNo = req.getWithdrawNo() != null && !req.getWithdrawNo().isBlank()
                ? req.getWithdrawNo() : noGenerator.nextWithdrawNo();
        accountService.freeze(merchantId, AccountRole.MERCHANT, withdrawNo, amount,
                autoWithdraw ? "自动提现冻结" : "提现申请冻结");

        SettWithdraw withdraw = new SettWithdraw();
        withdraw.setWithdrawNo(withdrawNo);
        withdraw.setMerchantId(merchantId);
        withdraw.setAmountFen(amount);
        withdraw.setFeeFen(fee);
        withdraw.setChannel(req.getChannel());
        // M-1：收款账号/姓名 AES-GCM 加密落库
        withdraw.setChannelAccount(dataCipher.encrypt(req.getChannelAccount()));
        withdraw.setAccountName(dataCipher.encrypt(req.getAccountName()));
        withdraw.setBankName(req.getBankName() == null ? "" : req.getBankName());
        withdraw.setStatus(WithdrawStatuses.APPLY);
        withdraw.setApplyDate(today);
        withdraw.setFreeOfCharge(free ? 1 : 0);
        withdraw.setAutoWithdraw(autoWithdraw ? 1 : 0);
        withdraw.setFailReason("");
        withdrawMapper.insert(withdraw);

        countMapper.bumpOnApply(merchantId, month, today, amount, fee > 0 ? 1 : 0);
        log.info("提现申请受理 withdrawNo={} merchantId={} amount={} fee={} free={} auto={}",
                withdrawNo, merchantId, amount, fee, free, autoWithdraw);
        return withdraw;
    }

    /** 审核批次：10→20（模拟风控自动过审）。返回本轮流转笔数。 */
    @Transactional
    public int auditBatch() {
        List<SettWithdraw> list = withdrawMapper.selectApplying(BATCH_LIMIT);
        int n = 0;
        for (SettWithdraw w : list) {
            int rows = withdrawMapper.update(null, new LambdaUpdateWrapper<SettWithdraw>()
                    .eq(SettWithdraw::getId, w.getId())
                    .eq(SettWithdraw::getStatus, WithdrawStatuses.APPLY)
                    .set(SettWithdraw::getStatus, WithdrawStatuses.AUDITING)
                    .set(SettWithdraw::getAuditTime, LocalDateTime.now()));
            if (rows == 1) {
                n++;
            }
        }
        return n;
    }

    /**
     * 打款提交批次（三段式第一段）：取申请日早于今日、状态 20、渠道尚未受理的单，
     * <b>事务外</b>逐单提交渠道代发（渠道幂等键=withdrawNo），受理成功后短事务落 channel_remit_no，
     * 单据保持状态 20（打款中）。终态由 {@link #queryPendingRemits} 查询确认推进——
     * 提交即成功的旧语义已废弃。单单异常不阻断整批，下轮按 withdrawNo 幂等重试。
     *
     * @return 本轮渠道受理笔数
     */
    public int remitBatch(LocalDate today) {
        List<SettWithdraw> list = withdrawMapper.selectRemittable(today, BATCH_LIMIT);
        int n = 0;
        for (SettWithdraw w : list) {
            if (submitRemit(w)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 提交单笔代发（无事务包裹：RPC 慢/失败不持有 DB 事务/连接）。
     *
     * @return true 渠道已受理并落账 channel_remit_no
     */
    public boolean submitRemit(SettWithdraw w) {
        RemitRequest request;
        try {
            request = RemitRequest.builder()
                    .bizNo(w.getWithdrawNo())
                    .merchantId(w.getMerchantId())
                    .channel(w.getChannel())
                    .channelAccount(dataCipher.decrypt(w.getChannelAccount()))
                    .accountName(dataCipher.decrypt(w.getAccountName()))
                    .bankName(w.getBankName() == null ? "" : w.getBankName())
                    .amountFen(w.getAmountFen())
                    .remark(w.getAutoWithdraw() != null && w.getAutoWithdraw() == 1 ? "自动提现" : "商户提现")
                    .build();
        } catch (Exception e) {
            log.error("提现收款账户解密失败，挂起待人工 withdrawNo={}", w.getWithdrawNo(), e);
            return false;
        }
        RemitResult result;
        try {
            result = remitRouter.route(w.getChannel()).remit(request);
        } catch (Exception e) {
            // 渠道异常不留终态：状态保持 20、无 channel_remit_no，批次下轮幂等重试
            log.error("提现代发提交异常 withdrawNo={}，下轮重试", w.getWithdrawNo(), e);
            return false;
        }
        if (!result.isAccepted()) {
            log.warn("提现代发受理未成功 withdrawNo={} reason={}", w.getWithdrawNo(), result.getFailReason());
            return false;
        }
        return self.markRemitAccepted(w, result.getChannelRemitNo());
    }

    /** 短事务：受理登记 CAS（仅首次落渠道流水号获胜）。 */
    @Transactional
    public boolean markRemitAccepted(SettWithdraw w, String channelRemitNo) {
        int rows = withdrawMapper.casMarkRemitNo(w.getId(), channelRemitNo);
        if (rows != 1) {
            // 并发/重复提交已被他节点受理，不算本轮受理
            return false;
        }
        log.info("提现代发已受理 withdrawNo={} merchantId={} amount={} channelRemitNo={}",
                w.getWithdrawNo(), w.getMerchantId(), w.getAmountFen(), channelRemitNo);
        return true;
    }

    /**
     * 查询补偿（三段式第二段，RemitQueryJob 每 60s）：逐单 touch 领取查询权后查渠道，
     * 成功走 {@link #confirmRemitSuccess}，失败走 {@link #markFailed}。
     *
     * @return 本轮确认成功笔数
     */
    public int queryPendingRemits(LocalDateTime now) {
        List<SettWithdraw> pending = withdrawMapper.selectRemitQueryPending(now.minusSeconds(30), BATCH_LIMIT);
        int confirmed = 0;
        for (SettWithdraw w : pending) {
            // touch 风格 CAS：多节点/多轮只有一个领取者真正查询，query_count 同步累加
            if (withdrawMapper.touchQuery(w.getWithdrawNo(), now.minusSeconds(30)) != 1) {
                continue;
            }
            RemitQueryResult queryResult;
            try {
                queryResult = remitRouter.route(w.getChannel()).query(RemitQueryRequest.builder()
                        .bizNo(w.getWithdrawNo())
                        .channel(w.getChannel())
                        .channelRemitNo(w.getChannelRemitNo())
                        .build());
            } catch (Exception e) {
                log.error("提现代发查询异常 withdrawNo={}，下轮重试", w.getWithdrawNo(), e);
                continue;
            }
            if (queryResult.getStatus() == RemitStatuses.SUCCESS) {
                if (self.confirmRemitSuccess(w)) {
                    confirmed++;
                }
            } else if (queryResult.getStatus() == RemitStatuses.FAIL) {
                self.markFailed(w.getWithdrawNo(), "渠道打款失败: " + queryResult.getFailReason());
            }
            // PROCESSING：保持 20 + channel_remit_no，下轮继续查询
        }
        return confirmed;
    }

    /**
     * 三段式第三段：渠道终态成功后的后置记账（短事务）。CAS 20→30 获胜方才
     * 解冻出款 + 手续费平台收入 23 + 发 WITHDRAW_RESULT(成功)；重放零副作用。
     */
    @Transactional
    public boolean confirmRemitSuccess(SettWithdraw w) {
        int rows = withdrawMapper.casRemitSuccess(w.getId());
        if (rows != 1) {
            return false;
        }
        accountService.unfreezeOut(w.getMerchantId(), AccountRole.MERCHANT, w.getWithdrawNo(),
                w.getAmountFen(), "提现渠道打款成功");
        if (w.getFeeFen() != null && w.getFeeFen() > 0) {
            accountService.creditAvailable(0L, AccountRole.PLATFORM, w.getWithdrawNo(),
                    FlowChangeTypes.WITHDRAW_FEE_INCOME, w.getFeeFen(), "提现手续费收入");
        }
        publishResult(w, WithdrawStatuses.SUCCESS, null);
        log.info("提现打款终态确认成功 withdrawNo={} merchantId={} amount={} fee={} channelRemitNo={}",
                w.getWithdrawNo(), w.getMerchantId(), w.getAmountFen(), w.getFeeFen(), w.getChannelRemitNo());
        return true;
    }

    /**
     * 渠道打款失败（审核期失败或受理后查询终态失败）：20→40，冻结退回可提现（流水 22），
     * 发 WITHDRAW_RESULT(失败)。channel_remit_no 保留备查；失败原因同时落 fail_reason 与
     * remit_fail_reason。支持管理端 POST /admin/withdraw/{no}/mark-failed 人工闭环。
     */
    @Transactional
    public void markFailed(String withdrawNo, String reason) {
        SettWithdraw w = requireWithdraw(withdrawNo);
        String failReason = reason == null ? "渠道打款失败" : reason;
        int rows = withdrawMapper.update(null, new LambdaUpdateWrapper<SettWithdraw>()
                .eq(SettWithdraw::getId, w.getId())
                .eq(SettWithdraw::getStatus, WithdrawStatuses.AUDITING)
                .set(SettWithdraw::getStatus, WithdrawStatuses.FAIL)
                .set(SettWithdraw::getFailReason, failReason)
                .set(SettWithdraw::getRemitFailReason, failReason));
        if (rows != 1) {
            throw new BizException(ErrorCode.CONFLICT, "提现单当前状态不允许标记失败: " + withdrawNo);
        }
        accountService.unfreezeBack(w.getMerchantId(), AccountRole.MERCHANT, withdrawNo,
                FlowChangeTypes.WITHDRAW_RETURN, w.getAmountFen(), "提现失败余额退回");
        publishResult(w, WithdrawStatuses.FAIL, failReason);
    }

    /** 审核拒绝：10/20→50，冻结退回可提现，发 WITHDRAW_RESULT(拒绝)。 */
    @Transactional
    public void refuse(String withdrawNo, String reason) {
        SettWithdraw w = requireWithdraw(withdrawNo);
        int rows = withdrawMapper.update(null, new LambdaUpdateWrapper<SettWithdraw>()
                .eq(SettWithdraw::getId, w.getId())
                .in(SettWithdraw::getStatus, WithdrawStatuses.APPLY, WithdrawStatuses.AUDITING)
                .set(SettWithdraw::getStatus, WithdrawStatuses.REFUSED)
                .set(SettWithdraw::getFailReason, reason == null ? "审核拒绝" : reason));
        if (rows != 1) {
            throw new BizException(ErrorCode.CONFLICT, "提现单当前状态不允许拒绝: " + withdrawNo);
        }
        accountService.unfreezeBack(w.getMerchantId(), AccountRole.MERCHANT, withdrawNo,
                FlowChangeTypes.WITHDRAW_RETURN, w.getAmountFen(), "提现拒绝余额退回");
        publishResult(w, WithdrawStatuses.REFUSED, reason);
    }

    /** 商户分页查询提现单（M-1：对外仅返回脱敏账号）。 */
    public PageResult<WithdrawVO> pageMerchant(long merchantId, int pageNum, int pageSize) {
        Page<SettWithdraw> page = new Page<>(pageNum, pageSize);
        Page<SettWithdraw> result = withdrawMapper.selectPage(page, new LambdaQueryWrapper<SettWithdraw>()
                .eq(SettWithdraw::getMerchantId, merchantId)
                .orderByDesc(SettWithdraw::getId));
        List<WithdrawVO> vos = result.getRecords().stream()
                .map(w -> WithdrawVO.masked(w, dataCipher))
                .toList();
        return PageResult.of(pageNum, pageSize, result.getTotal(), vos);
    }

    // ---------------- 自动提现 ----------------

    /** 查询自动提现配置（不存在返回关闭的默认对象；M-1：对外仅脱敏账号）。 */
    public AutoWithdrawConfigVO getConfigMasked(long merchantId) {
        return AutoWithdrawConfigVO.masked(getConfigEntity(merchantId), dataCipher);
    }

    /** 内部使用：含密文账号的配置实体（自动打款链路解密用）。 */
    private SettWithdrawAutoConfig getConfigEntity(long merchantId) {
        SettWithdrawAutoConfig config = autoConfigMapper.selectOne(new LambdaQueryWrapper<SettWithdrawAutoConfig>()
                .eq(SettWithdrawAutoConfig::getMerchantId, merchantId));
        if (config == null) {
            config = new SettWithdrawAutoConfig();
            config.setMerchantId(merchantId);
            config.setEnabled(0);
            config.setFrequency(AutoFrequencies.DAILY);
            config.setChannel(WithdrawChannels.BANK_CARD);
        }
        return config;
    }

    /** 新建/更新自动提现配置（M-1：账号加密落库，返回脱敏视图）。 */
    @Transactional
    public AutoWithdrawConfigVO saveConfig(long merchantId, AutoWithdrawConfigRequest req) {
        if (req.getEnabled() == null || (req.getEnabled() != 0 && req.getEnabled() != 1)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "开启状态非法");
        }
        if (!AutoFrequencies.valid(req.getFrequency())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "提现频率仅支持每日(1)/每周(2)");
        }
        if (!WithdrawChannels.valid(req.getChannel())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "提现渠道仅支持银行卡(1)/支付宝(2)");
        }
        if (req.getEnabled() == 1 && req.getFrequency() == AutoFrequencies.WEEKLY
                && (req.getWeekday() == null || req.getWeekday() < 1 || req.getWeekday() > 7)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "每周提现必须指定星期(1~7)");
        }
        merchantService.requireMerchant(merchantId);

        SettWithdrawAutoConfig config = autoConfigMapper.selectOne(new LambdaQueryWrapper<SettWithdrawAutoConfig>()
                .eq(SettWithdrawAutoConfig::getMerchantId, merchantId));
        boolean isNew = config == null;
        if (isNew) {
            config = new SettWithdrawAutoConfig();
            config.setMerchantId(merchantId);
        }
        config.setEnabled(req.getEnabled());
        config.setFrequency(req.getFrequency());
        config.setWeekday(req.getFrequency() == AutoFrequencies.WEEKLY ? req.getWeekday() : null);
        config.setChannel(req.getChannel());
        config.setChannelAccount(dataCipher.encrypt(req.getChannelAccount()));
        config.setAccountName(dataCipher.encrypt(req.getAccountName()));
        config.setBankName(req.getBankName() == null ? "" : req.getBankName());
        if (isNew) {
            autoConfigMapper.insert(config);
        } else {
            autoConfigMapper.updateById(config);
        }
        return AutoWithdrawConfigVO.masked(config, dataCipher);
    }

    /**
     * 自动提现批次（ShedLock 每日触发）：按配置为符合条件的商户生成申请单。
     *
     * @return 实际生成的提现单数
     */
    @Transactional
    public int runAutoWithdraw(LocalDate today) {
        List<SettWithdrawAutoConfig> configs = autoConfigMapper.selectEnabled();
        int created = 0;
        for (SettWithdrawAutoConfig config : configs) {
            if (!shouldRunToday(config, today)) {
                continue;
            }
            Long available = getAvailableBalance(config.getMerchantId());
            if (available == null || available < WithdrawCalculator.MIN_AMOUNT_FEN) {
                log.info("自动提现跳过：可提现余额不足100元 merchantId={} available={}",
                        config.getMerchantId(), available);
                continue;
            }
            ApplyWithdrawRequest req = new ApplyWithdrawRequest();
            req.setAmountFen(available);
            req.setChannel(config.getChannel());
            // M-1：自动打款链路使用解密后的完整收款账号
            req.setChannelAccount(dataCipher.decrypt(config.getChannelAccount()));
            req.setAccountName(dataCipher.decrypt(config.getAccountName()));
            req.setBankName(config.getBankName());
            doApply(merchantService.requireActiveMerchant(config.getMerchantId()), req, true);
            config.setLastRunDate(today);
            autoConfigMapper.updateById(config);
            created++;
        }
        return created;
    }

    /** 每日：当天未执行过；每周：星期匹配且当天未执行过。 */
    private boolean shouldRunToday(SettWithdrawAutoConfig config, LocalDate today) {
        if (today.equals(config.getLastRunDate())) {
            return false;
        }
        if (config.getFrequency() == AutoFrequencies.WEEKLY) {
            // java.time DayOfWeek: MONDAY=1 ... SUNDAY=7，与配置口径一致
            return config.getWeekday() != null && config.getWeekday() == today.getDayOfWeek().getValue();
        }
        return config.getFrequency() == AutoFrequencies.DAILY;
    }

    private Long getAvailableBalance(long merchantId) {
        return accountService.getOrCreate(merchantId, AccountRole.MERCHANT).getAvailableFen();
    }

    private SettWithdraw requireWithdraw(String withdrawNo) {
        SettWithdraw w = withdrawMapper.selectOne(new LambdaQueryWrapper<SettWithdraw>()
                .eq(SettWithdraw::getWithdrawNo, withdrawNo));
        if (w == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "提现单不存在: " + withdrawNo);
        }
        return w;
    }

    private void publishResult(SettWithdraw w, int status, String failReason) {
        WithdrawResultEvent event = WithdrawResultEvent.builder()
                .withdrawNo(w.getWithdrawNo())
                .merchantId(w.getMerchantId())
                .amountFen(w.getAmountFen())
                .status(status)
                .failReason(failReason)
                .build();
        event.setBizNo(w.getWithdrawNo());
        // P1-1：调用方（remitBatch/markFailed/refuse）均为 @Transactional，事件与提现状态、
        // 冻结/手续费记账同事务登记 outbox，commit 前崩溃不会产生幽灵「打款结果」
        outboxPublisher.publish(MqTopics.WITHDRAW_RESULT, null, event, w.getWithdrawNo());
    }
}
