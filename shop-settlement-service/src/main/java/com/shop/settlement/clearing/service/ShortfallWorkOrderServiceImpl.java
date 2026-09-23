package com.shop.settlement.clearing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.common.constant.MqTopics;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.clearing.entity.ShortfallWorkOrder;
import com.shop.settlement.clearing.entity.SettClearingReverse;
import com.shop.settlement.clearing.event.RefundShortfallEvent;
import com.shop.settlement.clearing.mapper.ClearingReverseMapper;
import com.shop.settlement.clearing.mapper.ShortfallWorkOrderMapper;
import com.shop.settlement.clearing.support.AlarmNotifier;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.deposit.mapper.DepositLogMapper;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.enums.DepositLogTypes;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.mapper.MerchantMapper;
import com.shop.settlement.mq.service.MqConsumeService;
import com.shop.settlement.support.SettleNoGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 穿仓缺口工单服务实现（GAP_PLAN_FUNDS P0-1）。
 *
 * <p>三层幂等：
 * <ol>
 *   <li>t_sett_mq_consume uk_event_id（实时消费）；</li>
 *   <li>工单 uk_event_id + reverse_no 先查后插（实时/扫表跨路径同一冲正恰一单；
 *       扫表只处理当日 0 点前的冲正，不与实时消费竞争；DDL 无 reverse_no 唯一键，
 *       严格并发唯一需补 DDL，见实施报告）；</li>
 *   <li>补扣 DP 流水 (biz_no, log_type=20) + 账户流水 UK(biz_no, change_type=31)；
 *       补扣与缴费入账同事务，余额驱动 LEAST 扣减使缴费事件重放自然零扣款。</li>
 * </ol>
 */
@Slf4j
@Service
public class ShortfallWorkOrderServiceImpl implements ShortfallWorkOrderService {

    /** 扫表/补告警分页大小（卡 P0-1：limit 200 逐页）。 */
    static final int PAGE_SIZE = 200;
    /** remark 列 VARCHAR(512)，留痕截断长度。 */
    private static final int REMARK_MAX = 480;

    private final ShortfallWorkOrderMapper workOrderMapper;
    private final ClearingReverseMapper reverseMapper;
    private final MqConsumeService mqConsumeService;
    private final AlarmNotifier alarmNotifier;
    private final MerchantMapper merchantMapper;
    private final DepositLogMapper depositLogMapper;
    private final AccountService accountService;
    private final SettleNoGenerator noGenerator;
    /**
     * 自代理：扫表逐行走 {@link #onShortfall} 的事务边界（单行失败不拖垮整扫，
     * 成功一行提交一行）。ObjectProvider 延迟取 Spring 代理，避免构造期自引用。
     */
    private final ObjectProvider<ShortfallWorkOrderService> selfProvider;
    // O6：无注册表环境（部分单测）为空，埋点空转安全
    private final MeterRegistry meterRegistry;

    @Autowired(required = false)
    public ShortfallWorkOrderServiceImpl(ShortfallWorkOrderMapper workOrderMapper,
                                         ClearingReverseMapper reverseMapper,
                                         MqConsumeService mqConsumeService,
                                         AlarmNotifier alarmNotifier,
                                         MerchantMapper merchantMapper,
                                         DepositLogMapper depositLogMapper,
                                         AccountService accountService,
                                         SettleNoGenerator noGenerator,
                                         ObjectProvider<ShortfallWorkOrderService> selfProvider,
                                         MeterRegistry meterRegistry) {
        this.workOrderMapper = workOrderMapper;
        this.reverseMapper = reverseMapper;
        this.mqConsumeService = mqConsumeService;
        this.alarmNotifier = alarmNotifier;
        this.merchantMapper = merchantMapper;
        this.depositLogMapper = depositLogMapper;
        this.accountService = accountService;
        this.noGenerator = noGenerator;
        this.selfProvider = selfProvider;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional
    public void onShortfall(RefundShortfallEvent e) {
        // ① 消费幂等：同 eventId 重放直接 ACK
        if (!mqConsumeService.tryRecord(e.getEventId(), MqTopics.REFUND_SHORTFALL,
                CG_SHORTFALL, e.getRefundNo())) {
            log.info("REFUND_SHORTFALL 重复消费直接ACK eventId={} refundNo={}",
                    e.getEventId(), e.getRefundNo());
            return;
        }
        // ② 同一冲正跨路径（实时 vs RESIDUAL 扫表）只开一张工单
        if (workOrderMapper.selectByReverseNo(e.getReverseNo()) != null) {
            log.info("穿仓工单已存在，幂等跳过 reverseNo={} eventId={}",
                    e.getReverseNo(), e.getEventId());
            return;
        }
        ShortfallWorkOrder wo = new ShortfallWorkOrder();
        wo.setEventId(e.getEventId());
        wo.setReverseNo(e.getReverseNo());
        wo.setRefundNo(e.getRefundNo());
        wo.setOrderNo(e.getOrderNo() == null ? "" : e.getOrderNo());
        wo.setMerchantId(e.getMerchantId());
        wo.setShortfallFen(nz(e.getShortfallFen()));
        wo.setClawedBackFen(0L);
        wo.setStatus(ShortfallWorkOrder.STATUS_PENDING);
        wo.setAlertCount(0);
        wo.setRemark("");
        try {
            workOrderMapper.insert(wo);
        } catch (DuplicateKeyException dup) {
            // uk_event_id 并发落败：另一消费者/路径已落单
            log.info("穿仓工单 uk_event_id 并发冲突，落败跳过 eventId={}", e.getEventId());
            return;
        }
        // ③ INSERT 获胜方：CAS 10→20 + alert_count+1，赢得方负责唯一一次初始告警
        if (workOrderMapper.casAlerted(wo.getId()) == 1) {
            alertQuietly(wo, e);
        }
    }

    @Override
    public void dailyRescan(LocalDate now) {
        // 只兜底当日 0 点之前的冲正：与实时消费无时间面竞争（ShedLock 保证单实例扫描）
        LocalDateTime before = now.atStartOfDay();
        log.info("穿仓缺口扫表兜底开始 before={} pageSize={}", before, PAGE_SIZE);

        // 通道一：selectSuspended 的 NOT EXISTS 在 SQL 侧排除已有工单的冲正，
        // 故固定 (before, limit) 签名即可逐页自然推进——上一页落单后下一页不再返回。
        while (true) {
            List<SettClearingReverse> batch = reverseMapper.selectSuspended(before, PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (SettClearingReverse r : batch) {
                try {
                    selfProvider.getObject().onShortfall(toResidualEvent(r));
                } catch (RuntimeException ex) {
                    // 单行失败不拖垮整扫，下轮兜底重试（未落单则 NOT EXISTS 仍会扫到）
                    log.error("穿仓扫表补单失败 reverseNo={} refundNo={}",
                            r.getReverseNo(), r.getRefundNo(), ex);
                }
            }
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }

        // 通道二：滞留 10 态（落单后告警 CAS 未达成的异常数据）补告警，键集分页
        long lastId = 0L;
        while (true) {
            List<ShortfallWorkOrder> page = workOrderMapper.selectByStatusPaged(
                    ShortfallWorkOrder.STATUS_PENDING, lastId, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (ShortfallWorkOrder wo : page) {
                if (workOrderMapper.casAlerted(wo.getId()) == 1) {
                    alertQuietly(wo, toEvent(wo));
                }
            }
            lastId = page.get(page.size() - 1).getId();
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        log.info("穿仓缺口扫表兜底完成 before={}", before);
    }

    @Override
    @Transactional
    public void clawbackOnDepositPaid(long merchantId) {
        // 固定序列与退款瀑布一致：FOR UPDATE 锁商户行 → 行内 LEAST 余额逐单扣减。
        // 本方法加入 B10 缴费确认事务（REQUIRED）：缴费入账与补扣原子，崩溃一起回滚。
        SettMerchant merchant = merchantMapper.selectForUpdate(merchantId);
        if (merchant == null) {
            log.warn("穿仓补扣商户不存在 merchantId={}", merchantId);
            return;
        }
        List<ShortfallWorkOrder> orders = workOrderMapper.selectOpenByMerchant(merchantId);
        if (orders.isEmpty()) {
            return;
        }
        long balance = nz(merchant.getDepositBalanceFen());
        log.info("保证金到账触发穿仓补扣 merchantId={} balance={} openOrders={}",
                merchantId, balance, orders.size());

        for (ShortfallWorkOrder wo : orders) {
            long shortfall = nz(wo.getShortfallFen());
            long clawed = nz(wo.getClawedBackFen());
            long remaining = shortfall - clawed;
            if (remaining <= 0) {
                workOrderMapper.casClosed(wo.getId());
                continue;
            }
            if (balance <= 0) {
                // 余额耗尽：后续工单全部保留 10/20，等待下一笔到账
                // O6：穿仓补扣因保证金不足停止（复用 shop_deposit_insufficient_total，event=CLAWBACK）
                if (meterRegistry != null) {
                    meterRegistry.counter(DepositService.DEPOSIT_INSUFFICIENT_TOTAL,
                            "event", DepositService.EVENT_CLAWBACK).increment();
                }
                break;
            }
            long actual = Math.min(balance, remaining);

            // 同一冲正多笔到账分期补扣：首期 biz_no=reverseNo（卡 P0-1 字面口径），
            // 后续期次 reverseNo#n，均受 DP/账户流水 (biz_no,type) 唯一约束幂等保护。
            int priorInstallments = countClawbackInstallments(wo.getReverseNo(), merchantId);
            String bizNo = priorInstallments == 0
                    ? wo.getReverseNo() : wo.getReverseNo() + "#" + (priorInstallments + 1);
            if (depositLogExists(bizNo)) {
                // 缴费事件重放/并发：该期补扣流水已在，不重复扣款
                log.info("穿仓补扣流水已存在，幂等跳过 merchantId={} bizNo={}", merchantId, bizNo);
                continue;
            }

            int rows = merchantMapper.changeDeposit(merchantId, -actual);
            if (rows != 1) {
                // 锁内余额充足却扣减失败属数据异常：抛错回滚整笔缴费事务，由上层重试
                throw new IllegalStateException(
                        "穿仓保证金补扣扣减失败 merchantId=" + merchantId + " bizNo=" + bizNo);
            }
            balance -= actual;

            // 保证金流水 DP：log_type=20 REFUND_COMPENSATE，biz_no=reverseNo（首期）
            SettDepositLog dpLog = new SettDepositLog();
            dpLog.setLogNo(noGenerator.nextDepositLogNo());
            dpLog.setMerchantId(merchantId);
            dpLog.setLogType(DepositLogTypes.REFUND_COMPENSATE);
            dpLog.setAmountFen(-actual);
            dpLog.setBalanceAfterFen(balance);
            dpLog.setBizNo(bizNo);
            dpLog.setRemark("穿仓缺口保证金自动补扣 " + wo.getRefundNo());
            depositLogMapper.insert(dpLog);

            // 账户流水 31 留痕（不移动三档余额，资金账见保证金流水），UK(biz_no,change_type) 幂等
            accountService.writeZeroFlow(merchantId, AccountRole.MERCHANT, bizNo,
                    FlowChangeTypes.REFUND_FROM_DEPOSIT,
                    "穿仓缺口保证金自动补扣 reverseNo=" + wo.getReverseNo() + " " + actual + "分");

            workOrderMapper.addClawedBack(wo.getId(), actual);
            int closed = workOrderMapper.casClosed(wo.getId());
            if (closed == 1) {
                log.info("穿仓工单已追缴结清 id={} reverseNo={} shortfall={}",
                        wo.getId(), wo.getReverseNo(), shortfall);
            } else {
                log.info("穿仓工单部分补扣保留 id={} reverseNo={} 本次={} 剩余缺口={}",
                        wo.getId(), wo.getReverseNo(), actual, remaining - actual);
            }
        }
    }

    /** 告警通道故障不回滚消费：catch 后落 remark（V5 无 last_error 列）并正常 ACK。 */
    private void alertQuietly(ShortfallWorkOrder wo, RefundShortfallEvent e) {
        try {
            alarmNotifier.notifyShortfall(e);
        } catch (RuntimeException ex) {
            log.error("穿仓告警通道异常，消费仍ACK workOrderId={} reverseNo={}",
                    wo.getId(), wo.getReverseNo(), ex);
            String note = "ALARM_FAIL:" + ex.getClass().getSimpleName()
                    + ":" + (ex.getMessage() == null ? "" : ex.getMessage());
            if (note.length() > REMARK_MAX) {
                note = note.substring(0, REMARK_MAX);
            }
            try {
                workOrderMapper.updateRemark(wo.getId(), note);
            } catch (RuntimeException sqlEx) {
                log.warn("告警失败留痕落库失败 workOrderId={}", wo.getId(), sqlEx);
            }
        }
    }

    /** 扫表补单：用冲正行字段重建事件，eventId=RESIDUAL-+reverseNo（避免与原事件冲突）。 */
    private RefundShortfallEvent toResidualEvent(SettClearingReverse r) {
        RefundShortfallEvent e = new RefundShortfallEvent();
        e.setEventId("RESIDUAL-" + r.getReverseNo());
        e.setBizNo(r.getRefundNo());
        e.setMerchantId(r.getMerchantId());
        e.setOrderNo(r.getOrderNo());
        e.setRefundNo(r.getRefundNo());
        e.setReverseNo(r.getReverseNo());
        e.setMerchantPartFen(nz(r.getReverseMerchantFen()));
        e.setFromPendingFen(nz(r.getFromPendingFen()));
        e.setFromAvailableFen(nz(r.getFromAvailableFen()));
        e.setFromDepositFen(nz(r.getFromDepositFen()));
        e.setShortfallFen(nz(r.getShortfallFen()));
        return e;
    }

    /** 滞留 10 态工单补告警：用工单列字段构造最小事件。 */
    private RefundShortfallEvent toEvent(ShortfallWorkOrder wo) {
        RefundShortfallEvent e = new RefundShortfallEvent();
        e.setEventId(wo.getEventId());
        e.setBizNo(wo.getRefundNo());
        e.setMerchantId(wo.getMerchantId());
        e.setOrderNo(wo.getOrderNo());
        e.setRefundNo(wo.getRefundNo());
        e.setReverseNo(wo.getReverseNo());
        e.setShortfallFen(nz(wo.getShortfallFen()));
        return e;
    }

    private boolean depositLogExists(String bizNo) {
        Long count = depositLogMapper.selectCount(new LambdaQueryWrapper<SettDepositLog>()
                .eq(SettDepositLog::getBizNo, bizNo)
                .eq(SettDepositLog::getLogType, DepositLogTypes.REFUND_COMPENSATE));
        return count != null && count > 0;
    }

    /** 统计该冲正已落的补扣期次数（biz_no=reverseNo 或 reverseNo#n）。 */
    private int countClawbackInstallments(String reverseNo, long merchantId) {
        Long count = depositLogMapper.selectCount(new LambdaQueryWrapper<SettDepositLog>()
                .eq(SettDepositLog::getMerchantId, merchantId)
                .eq(SettDepositLog::getLogType, DepositLogTypes.REFUND_COMPENSATE)
                .and(w -> w.eq(SettDepositLog::getBizNo, reverseNo)
                        .or().likeRight(SettDepositLog::getBizNo, reverseNo + "#")));
        return count == null ? 0 : count.intValue();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
