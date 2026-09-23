package com.shop.settlement.deposit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.aftersale.client.AftersaleClient;
import com.shop.api.aftersale.dto.MerchantDisputeDTO;
import com.shop.api.pay.client.PayClient;
import com.shop.api.pay.dto.CreatePaymentCommand;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.pay.enums.PayScenes;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.api.settlement.event.DepositAlertEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.idempotent.Idempotent;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.settlement.account.service.AccountService;import com.shop.settlement.deposit.dto.DepositPayRequest;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.deposit.mapper.DepositLogMapper;
import com.shop.settlement.deposit.vo.DepositPayVO;
import com.shop.settlement.enums.DepositLogTypes;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.enums.MerchantStatuses;
import com.shop.settlement.enums.WithdrawChannels;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.mapper.MerchantMapper;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.remit.RemitQueryRequest;
import com.shop.settlement.remit.RemitQueryResult;
import com.shop.settlement.remit.RemitRequest;
import com.shop.settlement.remit.RemitResult;
import com.shop.settlement.remit.RemitRouter;
import com.shop.settlement.remit.RemitStatuses;
import com.shop.settlement.support.DataCipher;
import com.shop.settlement.support.SettleNoGenerator;
import com.shop.settlement.withdraw.entity.SettWithdraw;
import com.shop.settlement.withdraw.mapper.WithdrawMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 保证金服务（design 7.6，GAP_PLAN_FUNDS B10 真实资金链路）：
 *
 * <ul>
 *   <li><b>缴费三段式（先款后账）</b>：短事务建 DP 日志（status=10，<b>不动余额</b>）→
 *       事务外调支付域 createPayment（payScene=4，orderNo=logNo）→ 回写 pay_no；
 *       到账由 {@link DepositPaySettlementService} 消费 ORDER_PAID 后 CAS 10→20 才入余额；</li>
 *   <li><b>罚款</b>：行锁校验余额禁负 → changeDeposit(-) → log30（clientToken 幂等）→
 *       平台账户 43 同事务入账 → 扣后低于 50% 复用 {@link #publishAlert}；</li>
 *   <li><b>清退退还（先账后款铁律）</b>：满 90 天 + aftersale 域未终结纠纷校验（settlement 不伪造
 *       无纠纷结论）→ 建 log40 status=10（余额保留）→ 事务外渠道代发（复用最近一次成功提现账户，
 *       无则 DEPOSIT_ALERT 人工挂起）→ {@code RemitQueryJob} 查询终态：成功 CAS 10→20 才置零 +
 *       商户 30 + 流水 41；失败 status=30 保留余额告警；</li>
 *   <li>退款瀑布扣赔、入驻/清退登记、分页查询沿用既有语义。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired(required = false))
public class DepositService {

    /** O6：保证金不足/低于 50% 拦截事件指标（命名冻结，仅 event 受控标签，禁带 merchantId）。 */
    public static final String DEPOSIT_INSUFFICIENT_TOTAL = "shop_deposit_insufficient_total";
    /** 事件受控值：罚款余额不足/扣后低于阈值。 */
    public static final String EVENT_FINE = "FINE";
    /** 事件受控值：清退退还挂起/失败，保证金无法闭环。 */
    public static final String EVENT_RESIGN = "RESIGN";
    /** 事件受控值：退款瀑布扣赔/穿仓补扣保证金不足。 */
    public static final String EVENT_CLAWBACK = "CLAWBACK";

    /** R4-25 DEPOSIT_ALERT 实例级预警类型（outbox 去重前缀，亦写入事件体 alertType）。 */
    public static final String ALERT_FINE = "FINE";
    /** 退款瀑布扣赔触发的阈值预警。 */
    public static final String ALERT_CLAW = "CLAW";
    /** 清退无收款账户人工挂起。 */
    public static final String ALERT_HANG = "HANG";
    /** 渠道打款失败。 */
    public static final String ALERT_FAIL = "FAIL";

    /** 清退观察期：90 天 */
    public static final int RESIGN_OBSERVE_DAYS = 90;
    /** 清退/查询批次单轮扫描上限 */
    public static final int REFUND_BATCH_LIMIT = 100;

    private final MerchantMapper merchantMapper;
    private final MerchantService merchantService;
    private final DepositLogMapper depositLogMapper;
    private final SettleNoGenerator noGenerator;
    // P1-1：DEPOSIT_ALERT 与保证金扣款/预警标记同事务登记 outbox，禁止事务内直发 MQ
    private final OutboxPublisher outboxPublisher;
    // B10：真实资金链路依赖
    private final PayClient payClient;
    private final RemitRouter remitRouter;
    private final AftersaleClient aftersaleClient;
    private final WithdrawMapper withdrawMapper;
    private final DataCipher dataCipher;
    private final AccountService accountService;
    // O6：无注册表环境（部分单测）为空，埋点空转安全
    private final MeterRegistry meterRegistry;

    /** 自注入：编排方法（无事务）调用短事务方法必须经过代理，避免 this 自调用导致事务失效 */
    @Lazy
    @Autowired
    private DepositService self;

    /**
     * 保证金是否低于应缴额 50%（限提阈值）：balance * 2 &lt; required。
     */
    public boolean belowAlertThreshold(SettMerchant merchant) {
        long required = merchant.getDepositRequiredFen() == null ? 0L : merchant.getDepositRequiredFen();
        long balance = merchant.getDepositBalanceFen() == null ? 0L : merchant.getDepositBalanceFen();
        return required > 0 && balance * 2 < required;
    }

    /** 保证金预警阈值（应缴额的 50%，分，奇数千级向上取整到分）。 */
    public long thresholdFen(SettMerchant merchant) {
        return (merchant.getDepositRequiredFen() + 1) / 2;
    }

    // ============================== 缴费（三段式） ==============================

    /**
     * 商户端发起保证金缴费（M-3）：幂等键 = merchantId + clientToken（缺省用预生成 logNo）。
     * 本方法<b>不持有事务</b>：短事务建单 → 事务外 RPC 支付域 → 短事务回写 payNo，
     * RPC 慢/失败绝不占用 DB 连接与事务。
     */
    @Idempotent(prefix = "settle:deposit",
            key = "#merchantId + ':' + (#request.clientToken != null ? #request.clientToken : #logNo)")
    public DepositPayVO initiateDepositPay(long merchantId, DepositPayRequest request, String logNo) {
        // ① 短事务建 DP 日志（status=10，不动余额；clientToken 重放返回原单）
        SettDepositLog depositLog = self.createPayLog(merchantId, request.getAmountFen(),
                logNo, request.getClientToken());
        // ② 事务外调支付域：orderNo=logNo，支付域 uk_order_no 天然幂等
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderNo(depositLog.getLogNo())
                .userId(merchantId)
                .payMethod(request.getPayMethod())
                .amountFen(request.getAmountFen())
                .subject("保证金缴费")
                .terminal(request.getTerminal())
                .payScene(PayScenes.DEPOSIT)
                .build();
        PaymentDTO payment;
        try {
            Result<PaymentDTO> result = payClient.createPayment(command);
            if (result == null || !result.isSuccess() || result.getData() == null
                    || result.getData().getPayNo() == null || result.getData().getPayNo().isBlank()) {
                throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                        "支付域建单失败，DP 日志保持待支付: " + depositLog.getLogNo());
            }
            payment = result.getData();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // DP 日志保持 10，客户端同 clientToken 重试即可；禁止伪成功
            throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                    "支付域建单异常，DP 日志保持待支付: " + depositLog.getLogNo());
        }
        // ③ 回写 payNo（首次写入 CAS，重放零副作用）
        depositLogMapper.updatePayNo(depositLog.getLogNo(), payment.getPayNo());
        return new DepositPayVO(depositLog.getLogNo(), payment.getPayNo(), payment.getPayUrl());
    }

    /** 短事务：建缴费 DP 日志（log_type=10、status=10、不动余额）；clientToken 重放返回原单。 */
    @Transactional
    public SettDepositLog createPayLog(long merchantId, long amountFen, String preassignedLogNo,
                                       String clientToken) {
        if (amountFen <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缴费金额必须大于0");
        }
        if (clientToken != null && !clientToken.isBlank()) {
            SettDepositLog existing = depositLogMapper.selectOne(new LambdaQueryWrapper<SettDepositLog>()
                    .eq(SettDepositLog::getMerchantId, merchantId)
                    .eq(SettDepositLog::getLogType, DepositLogTypes.PAY)
                    .eq(SettDepositLog::getBizNo, clientToken)
                    .orderByDesc(SettDepositLog::getId)
                    .last("LIMIT 1"));
            if (existing != null) {
                return existing;
            }
        }
        SettMerchant merchant = merchantService.requireMerchant(merchantId);
        long balance = merchant.getDepositBalanceFen() == null ? 0L : merchant.getDepositBalanceFen();
        return writeLog(merchantId, DepositLogTypes.PAY, SettDepositLog.STATUS_PROCESSING,
                amountFen, balance, clientToken == null ? "" : clientToken,
                "保证金缴纳（待支付）", preassignedLogNo);
    }

    // ============================== 罚款 ==============================

    /**
     * 平台罚款（POST /admin/deposit/fine）：行锁余额校验（禁负）→ 扣保证金 → log30
     * （bizNo=clientToken 幂等）→ 平台账户 43 同事务入账 → 扣后低于 50% 发 DEPOSIT_ALERT。
     */
    @Transactional
    public SettDepositLog fine(long merchantId, long amountFen, String reason, String clientToken) {
        if (amountFen <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "罚款金额必须大于0");
        }
        if (clientToken == null || clientToken.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "罚款幂等令牌不能为空");
        }
        // clientToken 重放：不重复扣
        SettDepositLog existing = depositLogMapper.selectOne(new LambdaQueryWrapper<SettDepositLog>()
                .eq(SettDepositLog::getMerchantId, merchantId)
                .eq(SettDepositLog::getLogType, DepositLogTypes.FINE)
                .eq(SettDepositLog::getBizNo, clientToken)
                .orderByDesc(SettDepositLog::getId)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        // 行锁校验余额（禁负），与 changeDeposit 的非负条件更新双保险
        SettMerchant locked = merchantMapper.selectForUpdate(merchantId);
        if (locked == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商户不存在: " + merchantId);
        }
        long balance = locked.getDepositBalanceFen() == null ? 0L : locked.getDepositBalanceFen();
        if (balance < amountFen) {
            // 穿仓追讨不通过罚款：余额不足拒绝，禁止余额为负
            recordInsufficient(EVENT_FINE);
            throw new BizException(ErrorCode.DEPOSIT_NOT_ENOUGH,
                    "保证金余额不足，无法罚款: balance=" + balance + " fine=" + amountFen);
        }
        int rows = merchantMapper.changeDeposit(merchantId, -amountFen);
        if (rows != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "保证金罚款扣减失败");
        }
        SettMerchant latest = merchantService.requireMerchant(merchantId);
        SettDepositLog fineLog = writeLog(merchantId, DepositLogTypes.FINE, SettDepositLog.STATUS_SUCCESS,
                -amountFen, latest.getDepositBalanceFen(), clientToken,
                "保证金罚款: " + (reason == null ? "" : reason), null);
        // 平台收入账户 43 入账，bizNo=logNo，(biz_no,change_type) UK 幂等，与扣款同事务
        accountService.creditAvailable(0L, AccountRole.PLATFORM, fineLog.getLogNo(),
                FlowChangeTypes.DEPOSIT_FINE_INCOME, amountFen, "保证金罚款入账");
        if (belowAlertThreshold(latest)
                && (latest.getDepositAlerted() == null || latest.getDepositAlerted() == 0)) {
            latest.setDepositAlerted(1);
            merchantMapper.updateById(latest);
            // O6：罚款扣后跌破 50% 阈值
            recordInsufficient(EVENT_FINE);
            // R4-25：实例级键（每笔罚款流水唯一），同商户充值复位后的再次罚款不再撞 UK
            publishAlert(latest, ALERT_FINE, fineLog.getLogNo(), ALERT_FINE + ":" + fineLog.getLogNo());
        }
        return fineLog;
    }

    // ============================== 退款瀑布扣赔（既有语义） ==============================

    /**
     * 退款瀑布第三档：保证金<b>部分</b>扣赔（P1-10）。固定序列：行锁 → LEAST 原子扣
     * min(need, deposit) → log20（bizNo=refundNo）；扣后跌破 50% 阈值置预警 + DEPOSIT_ALERT outbox。
     * 余额清零也视为成功（返回实际扣减值），不抛 DEPOSIT_NOT_ENOUGH。
     */
    @Transactional
    public DepositDeductResult deductPartialForRefund(long merchantId, long need, String refundNo) {
        if (need <= 0) {
            return new DepositDeductResult(0L, merchantService.requireMerchant(merchantId).getDepositBalanceFen());
        }
        SettMerchant locked = merchantMapper.selectForUpdate(merchantId);
        long balanceBefore = locked == null || locked.getDepositBalanceFen() == null
                ? 0L : locked.getDepositBalanceFen();
        if (balanceBefore <= 0) {
            return new DepositDeductResult(0L, 0L);
        }
        int rows = merchantMapper.changeDepositPartial(merchantId, need);
        if (rows != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "保证金原子扣减失败: " + refundNo);
        }
        long actual = Math.min(need, balanceBefore);
        SettMerchant latest = merchantService.requireMerchant(merchantId);
        writeLog(merchantId, DepositLogTypes.REFUND_COMPENSATE, SettDepositLog.STATUS_SUCCESS, -actual,
                latest.getDepositBalanceFen(), refundNo, "退款清算保证金扣赔", null);
        if (belowAlertThreshold(latest)
                && (latest.getDepositAlerted() == null || latest.getDepositAlerted() == 0)) {
            latest.setDepositAlerted(1);
            merchantMapper.updateById(latest);
            // O6：退款瀑布扣赔后跌破 50% 阈值
            recordInsufficient(EVENT_CLAWBACK);
            // R4-25：实例级键（每笔退款单唯一），多笔扣赔/历史预警共存不再撞 UK
            publishAlert(latest, ALERT_CLAW, refundNo, ALERT_CLAW + ":" + refundNo);
        }
        return new DepositDeductResult(actual, latest.getDepositBalanceFen());
    }

    /** 商户清退登记（进入 90 天观察期）。 */
    @Transactional
    public void resign(long merchantId) {
        SettMerchant merchant = merchantService.requireActiveMerchant(merchantId);
        merchant.setStatus(MerchantStatuses.RESIGNING);
        merchant.setResignTime(LocalDateTime.now());
        merchantMapper.updateById(merchant);
    }

    // ============================== 清退退还（90 天 + 纠纷校验 + 真实代发） ==============================

    /**
     * 扫描清退登记满 90 天商户：aftersale 域未终结纠纷校验 → 建 log40 status=10 → 事务外渠道代发。
     * 本方法为批次编排（单商户独立短事务 + 事务外 RPC），不加全局事务。
     *
     * @return 本轮推进（建单/重新提交受理）的商户ID
     */
    public List<Long> scanAndRefundResigned(LocalDateTime now) {
        LocalDateTime deadline = now.minusDays(RESIGN_OBSERVE_DAYS);
        List<SettMerchant> merchants = merchantService.listByStatus(MerchantStatuses.RESIGNING);
        java.util.List<Long> advanced = new java.util.ArrayList<>();
        for (SettMerchant merchant : merchants) {
            if (merchant.getResignTime() == null || merchant.getResignTime().isAfter(deadline)) {
                continue;
            }
            try {
                if (handleResignedMerchant(merchant)) {
                    advanced.add(merchant.getId());
                }
            } catch (Exception e) {
                // 单商户失败不阻断整批；次日批次重试（bizNo/期号 + 渠道 bizNo 双幂等）
                log.error("保证金清退处理异常 merchantId={}，次日重试", merchant.getId(), e);
            }
        }
        return advanced;
    }

    /** @return true 本轮有推进（建退还单/重新提交代发/直接关单） */
    private boolean handleResignedMerchant(SettMerchant merchant) {
        long merchantId = merchant.getId();
        String periodBizNo = refundPeriodBizNo(merchant);

        // ① aftersale 域纠纷校验：settlement 不伪造「无纠纷」结论，以售后域返回为准
        Boolean exists;
        try {
            Result<MerchantDisputeDTO> r = aftersaleClient.existsOpenDispute(merchantId,
                    merchant.getResignTime());
            exists = r != null && r.isSuccess() && r.getData() != null
                    ? r.getData().getExists() : null;
        } catch (Exception e) {
            log.error("aftersale 纠纷校验失败 merchantId={}，跳过本轮（不打款不清零）", merchantId, e);
            return false;
        }
        if (exists == null) {
            log.error("aftersale 纠纷校验返回空 merchantId={}，跳过本轮", merchantId);
            return false;
        }
        if (Boolean.TRUE.equals(exists)) {
            // 存在未终结售后/介入单：次日再判，不清零不打款
            log.warn("商户存在未终结售后/介入单，暂缓清退退还 merchantId={}", merchantId);
            return false;
        }

        // ② 期号幂等：已建过 log40 的按状态推进，不重复建单/代发
        SettDepositLog refundLog = depositLogMapper.selectOne(new LambdaQueryWrapper<SettDepositLog>()
                .eq(SettDepositLog::getMerchantId, merchantId)
                .eq(SettDepositLog::getLogType, DepositLogTypes.RESIGN_REFUND)
                .eq(SettDepositLog::getBizNo, periodBizNo)
                .orderByDesc(SettDepositLog::getId)
                .last("LIMIT 1"));
        long balance = merchant.getDepositBalanceFen() == null ? 0L : merchant.getDepositBalanceFen();
        if (balance <= 0) {
            // 无余额：无需打款，直接关单商户状态
            if (refundLog == null) {
                self.createZeroBalanceClose(merchantId, periodBizNo);
            } else if (refundLog.getStatus() == SettDepositLog.STATUS_PROCESSING
                    && (refundLog.getChannelRemitNo() == null || refundLog.getChannelRemitNo().isBlank())) {
                // 建单后余额被瀑布扣光的极端场景：挂人工，不代发 0 元单
                log.warn("退还单金额期间被扣为0，挂人工处理 merchantId={} logNo={}",
                        merchantId, refundLog.getLogNo());
                return false;
            }
            closeMerchantResigned(merchant);
            return true;
        }
        if (refundLog == null) {
            // 短事务建退还单（status=10，余额保留；bizNo=merchantId+期号幂等）
            refundLog = self.createRefundLog(merchant, balance, periodBizNo);
        } else if (refundLog.getStatus() != SettDepositLog.STATUS_PROCESSING
                || (refundLog.getChannelRemitNo() != null && !refundLog.getChannelRemitNo().isBlank())) {
            // 20 成功由查询 Job 关单；30 失败人工处理；10 已受理待查询 Job 确认——本轮不再重复提交
            return false;
        }

        // ③ 收款账户：复用最近一次成功提现账户；无则人工挂起 DEPOSIT_ALERT（产品/环境残留）
        SettWithdraw lastSuccess = withdrawMapper.selectLatestSuccess(merchantId);
        if (lastSuccess == null) {
            log.error("商户无成功提现账户可复用，清退退还挂人工 merchantId={} logNo={}",
                    merchantId, refundLog.getLogNo());
            // DEPOSIT_ALERT 与消费记录同事务登记（产品残留：无商户结算账户绑定，转人工挂起）
            self.alertManualHang(merchantId, refundLog.getLogNo());
            return false;
        }

        // ④ 事务外渠道代发（渠道幂等键=logNo）；受理失败/异常保留 status=10 次日重试
        RemitRequest remitRequest;
        try {
            remitRequest = RemitRequest.builder()
                    .bizNo(refundLog.getLogNo())
                    .merchantId(merchantId)
                    .channel(lastSuccess.getChannel())
                    .channelAccount(dataCipher.decrypt(lastSuccess.getChannelAccount()))
                    .accountName(dataCipher.decrypt(lastSuccess.getAccountName()))
                    .bankName(lastSuccess.getBankName() == null ? "" : lastSuccess.getBankName())
                    .amountFen(balance)
                    .remark("保证金清退退还")
                    .build();
        } catch (Exception e) {
            log.error("退还收款账户解密失败 merchantId={} logNo={}，挂人工", merchantId, refundLog.getLogNo(), e);
            return false;
        }
        RemitResult result;
        try {
            result = remitRouter.route(lastSuccess.getChannel()).remit(remitRequest);
        } catch (Exception e) {
            log.error("退还代发提交异常 merchantId={} logNo={}，次日重试", merchantId, refundLog.getLogNo(), e);
            return false;
        }
        if (!result.isAccepted()) {
            log.warn("退还代发受理未成功 merchantId={} logNo={} reason={}",
                    merchantId, refundLog.getLogNo(), result.getFailReason());
            return false;
        }
        depositLogMapper.markRemitAccepted(refundLog.getLogNo(), result.getChannelRemitNo());
        log.info("保证金退还代发已受理 merchantId={} logNo={} amount={} channelRemitNo={}",
                merchantId, refundLog.getLogNo(), balance, result.getChannelRemitNo());
        return true;
    }

    /** 短事务：建清退退还日志（log_type=40、status=10、不动余额）。 */
    @Transactional
    public SettDepositLog createRefundLog(SettMerchant merchant, long balance, String periodBizNo) {
        return writeLog(merchant.getId(), DepositLogTypes.RESIGN_REFUND, SettDepositLog.STATUS_PROCESSING,
                -balance, balance, periodBizNo, "清退观察期满90天，保证金退还打款中", null);
    }

    /** 短事务：余额为 0 的商户写一笔 0 元退还留痕并直接置已清退。 */
    @Transactional
    public void createZeroBalanceClose(long merchantId, String periodBizNo) {
        writeLog(merchantId, DepositLogTypes.RESIGN_REFUND, SettDepositLog.STATUS_SUCCESS,
                0L, 0L, periodBizNo, "清退时保证金余额为0，无需打款", null);
        closeMerchantResigned(merchantService.requireMerchant(merchantId));
    }

    private void closeMerchantResigned(SettMerchant merchant) {
        if (merchant.getStatus() != null && merchant.getStatus() == MerchantStatuses.RESIGNING) {
            merchant.setStatus(MerchantStatuses.RESIGNED);
            merchantMapper.updateById(merchant);
        }
    }

    /**
     * 查询补偿：扫描已受理待终态的退还单，逐单 touch 领取后查渠道。
     * 成功 CAS 10→20 才置零余额 + 商户 30 + 流水 41；失败 CAS 10→30 保留余额告警。
     *
     * @return 本轮确认成功的笔数
     */
    public int queryPendingRefunds(LocalDateTime now) {
        List<SettDepositLog> pending = depositLogMapper.selectRefundRemitPending(REFUND_BATCH_LIMIT);
        LocalDateTime touchBefore = now.minusSeconds(30);
        int confirmed = 0;
        for (SettDepositLog refundLog : pending) {
            // touch 风格 CAS：多节点/多轮只有一个领取者真正发起查询
            if (depositLogMapper.touchQuery(refundLog.getLogNo(), touchBefore) != 1) {
                continue;
            }
            SettWithdraw lastSuccess = withdrawMapper.selectLatestSuccess(refundLog.getMerchantId());
            int channel = lastSuccess != null && lastSuccess.getChannel() != null
                    ? lastSuccess.getChannel() : WithdrawChannels.BANK_CARD;
            RemitQueryResult queryResult;
            try {
                queryResult = remitRouter.route(channel).query(RemitQueryRequest.builder()
                        .bizNo(refundLog.getLogNo())
                        .channel(channel)
                        .channelRemitNo(refundLog.getChannelRemitNo())
                        .build());
            } catch (Exception e) {
                log.error("退还代发查询异常 logNo={}，下轮重试", refundLog.getLogNo(), e);
                continue;
            }
            if (queryResult.getStatus() == RemitStatuses.SUCCESS) {
                if (self.confirmRefundSuccess(refundLog, queryResult.getChannelRemitNo())) {
                    confirmed++;
                }
            } else if (queryResult.getStatus() == RemitStatuses.FAIL) {
                self.failRefund(refundLog, queryResult.getFailReason());
            }
            // PROCESSING：保持 10，下轮继续查询
        }
        return confirmed;
    }

    /** 短事务：渠道确认成功后的后置记账（仅 CAS 获胜方执行）。 */
    @Transactional
    public boolean confirmRefundSuccess(SettDepositLog refundLog, String channelRemitNo) {
        if (depositLogMapper.casStatus(refundLog.getLogNo(),
                SettDepositLog.STATUS_PROCESSING, SettDepositLog.STATUS_SUCCESS) != 1) {
            return false;
        }
        // 渠道确认前绝不扣减余额：置零发生在此处
        SettMerchant locked = merchantMapper.selectForUpdate(refundLog.getMerchantId());
        long current = locked == null || locked.getDepositBalanceFen() == null
                ? 0L : locked.getDepositBalanceFen();
        long expected = Math.abs(refundLog.getAmountFen());
        if (current != expected) {
            // 观察期内瀑布扣赔等导致余额变动：按渠道实付金额扣减，差额留痕（异常监控）
            log.warn("退还确认时余额与代发额不一致 merchantId={} logNo={} current={} remit={}",
                    refundLog.getMerchantId(), refundLog.getLogNo(), current, expected);
        }
        if (current > 0) {
            int rows = merchantMapper.changeDeposit(refundLog.getMerchantId(), -current);
            if (rows != 1) {
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "清退退还置零失败: " + refundLog.getLogNo());
            }
        }
        closeMerchantResigned(merchantService.requireMerchant(refundLog.getMerchantId()));
        // 账户侧 41 流水（bizNo=logNo，UK(biz_no,change_type) 幂等）；资金账以保证金流水为准
        accountService.writeZeroFlow(refundLog.getMerchantId(), AccountRole.MERCHANT,
                refundLog.getLogNo(), FlowChangeTypes.DEPOSIT_REFUND, "清退保证金打款成功");
        log.info("保证金清退退还成功 merchantId={} logNo={} amount={} channelRemitNo={}",
                refundLog.getMerchantId(), refundLog.getLogNo(), current, channelRemitNo);
        return true;
    }

    /**
     * 短事务：无可用收款账户的清退挂起，同事务登记 DEPOSIT_ALERT outbox。
     * <p>R4-25：hang_alerted CAS 边沿闸门——每笔 log40 退还单只挂起告警一次，
     * 每日 03:00 重扫/多节点竞争时 CAS 未获胜即静默跳过，不再重发撞 outbox UK。</p>
     */
    @Transactional
    public void alertManualHang(long merchantId, String logNo) {
        if (depositLogMapper.casHangAlerted(logNo) != 1) {
            log.warn("清退挂起预警已登记过，跳过重复告警 merchantId={} logNo={}", merchantId, logNo);
            return;
        }
        // O6：清退退还因无收款账户挂人工，保证金无法闭环
        recordInsufficient(EVENT_RESIGN);
        publishAlert(merchantService.requireMerchant(merchantId),
                ALERT_HANG, logNo, ALERT_HANG + ":" + logNo);
    }

    /** 短事务：渠道确认失败，status 10→30 保留余额并 DEPOSIT_ALERT 人工处理。 */
    @Transactional
    public void failRefund(SettDepositLog refundLog, String failReason) {
        if (depositLogMapper.casStatus(refundLog.getLogNo(),
                SettDepositLog.STATUS_PROCESSING, SettDepositLog.STATUS_FAIL) != 1) {
            return;
        }
        // t_sett_deposit_log 无 remit_fail_reason 列（V5 缺列，见实施报告），失败原因并入 remark
        String merged = (refundLog.getRemark() == null ? "" : refundLog.getRemark())
                + " | 渠道打款失败: " + (failReason == null ? "" : failReason);
        SettDepositLog update = new SettDepositLog();
        update.setId(refundLog.getId());
        update.setRemark(merged.length() > 250 ? merged.substring(0, 250) : merged);
        depositLogMapper.updateById(update);
        SettMerchant merchant = merchantService.requireMerchant(refundLog.getMerchantId());
        // O6：渠道打款失败导致清退退还挂起，保证金无法闭环
        recordInsufficient(EVENT_RESIGN);
        log.error("保证金清退退还失败，余额保留并告警 merchantId={} logNo={} reason={}",
                merchant.getId(), refundLog.getLogNo(), failReason);
        // R4-25：实例级键（每笔退还单唯一）；CAS 10→30 保证每笔仅走一次，键再与历史路径天然隔离
        publishAlert(merchant, ALERT_FAIL, refundLog.getLogNo(), ALERT_FAIL + ":" + refundLog.getLogNo());
    }

    // ============================== 查询 / 预警 ==============================

    /** 平台端分页查询保证金流水。 */
    public PageResult<SettDepositLog> adminPage(Long merchantId, int pageNum, int pageSize) {
        long offset = (long) Math.max(pageNum - 1, 0) * pageSize;
        List<SettDepositLog> list = depositLogMapper.selectPage(merchantId, offset, pageSize);
        LambdaQueryWrapper<SettDepositLog> wrapper = new LambdaQueryWrapper<SettDepositLog>()
                .eq(merchantId != null, SettDepositLog::getMerchantId, merchantId);
        long total = depositLogMapper.selectCount(wrapper);
        return PageResult.of(pageNum, pageSize, total, list);
    }

    /** 退还期号幂等键：RF + 商户 + 清退登记日。 */
    private String refundPeriodBizNo(SettMerchant merchant) {
        return "RF:" + merchant.getId() + ":" + merchant.getResignTime().toLocalDate();
    }

    /**
     * 登记 DEPOSIT_ALERT outbox（调用方均为 @Transactional：事件与余额/预警标记同事务）。
     *
     * <p>R4-25：bizKey 必须是<b>实例级</b>去重键（{code 类型前缀:业务单号}），
     * 旧实现四路共用裸 merchantId——商户终生第一条预警后，罚款/扣赔/挂起/失败
     * 必撞 uk_topic_tag_bizkey 并回滚整个资金事务。事件体 bizNo 仍为 merchantId
     * （本 topic 无消费者，保持载荷口径稳定），alertType/refNo 承载实例维度。</p>
     *
     * <p>末道防线：outbox 插入若仍因 UK 碰撞失败（历史脏键/并发重放），降级为
     * error 日志而不是让异常逃出资金事务——预警可缺失，资金记账不可回滚。</p>
     */
    void publishAlert(SettMerchant merchant, String alertType, String refNo, String outboxBizKey) {
        long balance = merchant.getDepositBalanceFen() == null ? 0L : merchant.getDepositBalanceFen();
        DepositAlertEvent event = DepositAlertEvent.builder()
                .merchantId(merchant.getId())
                .alertType(alertType)
                .refNo(refNo)
                .balanceFen(balance)
                .thresholdFen(thresholdFen(merchant))
                .build();
        event.setBizNo(String.valueOf(merchant.getId()));
        try {
            outboxPublisher.publish(MqTopics.DEPOSIT_ALERT, null, event, outboxBizKey);
        } catch (DuplicateKeyException e) {
            // 失败的只是这条 INSERT 语句，Spring/MySQL 均不会据此回滚整个事务；告警此前已登记即可
            log.error("保证金预警outbox键已存在，跳过重复预警 merchantId={} type={} refNo={} key={}",
                    merchant.getId(), alertType, refNo, outboxBizKey, e);
        }
        log.warn("保证金低于50%预警/清退挂起 merchantId={} type={} refNo={} balance={} required={}",
                merchant.getId(), alertType, refNo, balance, merchant.getDepositRequiredFen());
    }

    /** O6：保证金不足/低于 50% 拦截计数；仅 event 受控标签（FINE/RESIGN/CLAWBACK），严禁 merchantId 入标签。 */
    public void recordInsufficient(String event) {
        if (meterRegistry != null) {
            meterRegistry.counter(DEPOSIT_INSUFFICIENT_TOTAL, "event", event).increment();
        }
    }

    private SettDepositLog writeLog(long merchantId, int type, int status, long amount, long balanceAfter,
                                    String bizNo, String remark, String preassignedLogNo) {
        SettDepositLog depositLog = new SettDepositLog();
        depositLog.setLogNo(preassignedLogNo != null && !preassignedLogNo.isBlank()
                ? preassignedLogNo : noGenerator.nextDepositLogNo());
        depositLog.setMerchantId(merchantId);
        depositLog.setLogType(type);
        depositLog.setStatus(status);
        depositLog.setPayNo("");
        depositLog.setChannelRemitNo("");
        depositLog.setAmountFen(amount);
        depositLog.setBalanceAfterFen(balanceAfter);
        depositLog.setBizNo(bizNo == null ? "" : bizNo);
        depositLog.setRemark(remark);
        depositLogMapper.insert(depositLog);
        return depositLog;
    }
}
