package com.shop.settlement.deposit.job;

import com.shop.api.pay.client.PayClient;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.pay.enums.PayScenes;
import com.shop.api.pay.enums.PayStatuses;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.common.result.Result;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.deposit.mapper.DepositLogMapper;
import com.shop.settlement.deposit.service.DepositPaySettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * R4-24 保证金缴费到账对账兜底 Job（资金正确性最后一道防线）。
 *
 * <p><b>事故背景：</b>{@code shop_order_paid} 被两个独立消费组订阅（cg_sett_paid 清算、
 * cg_sett_deposit_pay 保证金入账），R4-24 前 t_sett_mq_consume 唯一键只有 event_id，
 * 先登记的消费组使另一组 INSERT IGNORE 静默返回 0、消息照常 ACK——保证金到账事件被
 * 静默吞掉：缴费单永久停在 status=10、商户保证金余额不增、无错误日志、broker 无重投。
 * 触发窗口包括滚动发布/扩容的启动追赶期，HA 每次发布都可能发生。</p>
 *
 * <p><b>兜底语义：</b>周期扫描「建单超过在途宽限期、pay_no 已回写、仍 status=10」的
 * 缴费单，以支付域为资金事实源回查支付单；确属支付成功（status=30）且 orderNo/金额
 * 严格一致时，合成 payScene=4 的到账事件直接驱动
 * {@link DepositPaySettlementService#onPaymentSucceeded} 补账，复用其全部三重幂等
 * （mq_consume、CAS 10→20、流水 UK）与入账/补扣事务，补账过程与正常 MQ 路径完全同构。
 * 查过非成功的单子按 last_query_time 降频复查，避免废弃支付意向占满批次。</p>
 *
 * <p><b>R4-24 加固（评审项）：</b></p>
 * <ul>
 *   <li>陈旧 payNo 漏洞：首次支付 FAIL(40)/CLOSED(50) 后缴费单 pay_no 永久停留旧值
 *       （updatePayNo 只写空串），用户用同 clientToken 重新支付会产生新 payNo 的活跃单。
 *       存档 payNo 查到终态单时，必须按 orderNo（=logNo）回查当前活跃支付单再判定，
 *       否则对账永远只摸得到死单、新单到账无法补。</li>
 *   <li>补账计数只认真实入账：以 onPaymentSucceeded 返回的 CAS 获胜布尔为准，
 *       他节点/他轮次已补的幂等落败不计 recovered。</li>
 * </ul>
 *
 * <p><b>安全红线：</b>支付域查不到/状态非成功/orderNo 或金额不一致一律不补账（后者
 * 打 ERROR 等待人工对账）；Feign 异常不 touch、不吞，下一轮自然重试。ShedLock 保证
 * 集群单节点执行；CAS 是多节点/多轮的二道防线。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepositPayRecoveryJob {

    /** 单轮扫描上限。 */
    public static final int BATCH_LIMIT = 100;
    /** 非成功支付单两次复查的最小间隔（节流废弃支付意向）。 */
    public static final long RECHECK_INTERVAL_SECONDS = 600L;

    private final DepositLogMapper depositLogMapper;
    private final PayClient payClient;
    private final DepositPaySettlementService depositPaySettlementService;

    /** 建单后多久仍 status=10 才进入对账（避开支付回调→MQ 的正常在途窗口）。 */
    @Value("${shop.settle.deposit.pay-recovery-delay-seconds:90}")
    private long recoveryDelaySeconds;

    /**
     * 锁时长放宽到 5 分钟：极端情况下一批 100 笔各一次串行 Feign，PT2M 可能在批处理
     * 途中到期（到期只影响防重，CAS/touch 仍是二道防线，放宽是消除窗口而非修正确性）。
     */
    @Scheduled(fixedDelayString = "${shop.settle.deposit.pay-recovery-interval-ms:60000}")
    @SchedulerLock(name = "settle:deposit-pay-recovery", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void run() {
        try {
            int recovered = recoverOnce(LocalDateTime.now());
            if (recovered > 0) {
                log.info("保证金缴费到账对账补账完成，本轮补账 {} 笔", recovered);
            }
        } catch (Exception e) {
            // 单轮整体异常不允许杀死调度线程；下一轮继续
            log.error("保证金缴费到账对账兜底批异常", e);
        }
    }

    /**
     * 执行一轮对账补账（包可见以便单测直接驱动）。
     *
     * @return 本轮真实完成入账（CAS 10→20 获胜）的笔数
     */
    public int recoverOnce(LocalDateTime now) {
        List<SettDepositLog> pending = depositLogMapper.selectPayPendingForRecovery(
                now.minusSeconds(recoveryDelaySeconds),
                now.minusSeconds(RECHECK_INTERVAL_SECONDS),
                BATCH_LIMIT);
        int recovered = 0;
        for (SettDepositLog depositLog : pending) {
            if (recoverOne(depositLog)) {
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * @return true 本轮真实入账（CAS 获胜）；false 未成功/挂起/幂等落败/待重试
     */
    private boolean recoverOne(SettDepositLog depositLog) {
        String logNo = depositLog.getLogNo();
        String payNo = depositLog.getPayNo();
        PaymentDTO payment;
        try {
            payment = resolvePayment(logNo, payNo);
        } catch (Exception e) {
            // Feign 故障：不 touch、不吞，下一轮重试；禁止在支付事实不可达时补账
            log.error("保证金到账对账：查询支付域异常，下轮重试 logNo={} payNo={}", logNo, payNo, e);
            return false;
        }

        if (payment == null) {
            // 无成功支付且无活跃支付意向：降频复查（用户日后重新支付成功时仍会被捞到）
            depositLogMapper.touchPayRecoveryQuery(logNo);
            return false;
        }

        if (!Objects.equals(payment.getStatus(), PayStatuses.SUCCESS.getCode())) {
            // 待支付/支付中：降频复查（用户可能延迟支付成功），不允许补账
            depositLogMapper.touchPayRecoveryQuery(logNo);
            return false;
        }

        // 资金一致性硬校验：支付单必须就是本缴费单，金额分毫不差，否则宁可挂起人工对账
        if (!Objects.equals(payment.getOrderNo(), logNo)) {
            log.error("保证金到账对账命中 orderNo 不一致，挂起人工核对，禁止自动补账 "
                    + "logNo={} payNo={} payOrderNo={}", logNo, payment.getPayNo(), payment.getOrderNo());
            depositLogMapper.touchPayRecoveryQuery(logNo);
            return false;
        }
        if (!Objects.equals(payment.getAmountFen(), depositLog.getAmountFen())) {
            log.error("保证金到账对账命中金额不一致，挂起人工核对，禁止自动补账 "
                    + "logNo={} payNo={} payAmount={} logAmount={}", logNo, payment.getPayNo(),
                    payment.getAmountFen(), depositLog.getAmountFen());
            depositLogMapper.touchPayRecoveryQuery(logNo);
            return false;
        }

        // 合成到账事件：确定性 eventId（rec- 前缀 + payNo）使多轮/多节点补账自身幂等，
        // 与原始 MQ 事件 eventId 不冲突；入账事务内的 CAS/流水 UK 仍是最终防线。
        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .payNo(payment.getPayNo())
                .orderNo(payment.getOrderNo())
                .userId(payment.getUserId())
                .payMethod(payment.getPayMethod())
                .amountFen(payment.getAmountFen())
                .channelTransactionNo(payment.getChannelTransactionNo())
                .paidTime(payment.getPayTime())
                .payScene(PayScenes.DEPOSIT)
                .build();
        event.setEventId("rec-" + payment.getPayNo());
        event.setBizNo(logNo);
        try {
            // 只有 CAS 10→20 真实获胜才计补账；幂等落败（他节点/他轮次已补）返回 false
            boolean booked = depositPaySettlementService.onPaymentSucceeded(event);
            if (booked) {
                log.warn("保证金到账对账兜底补账已驱动（正常 MQ 到账链路曾缺失）logNo={} payNo={} amount={}",
                        logNo, payment.getPayNo(), payment.getAmountFen());
            }
            return booked;
        } catch (Exception e) {
            // 金额/DB 等错误：不 touch，60s 后重试；连续失败依赖日志告警与人工对账
            log.error("保证金到账对账补账失败，下轮重试 logNo={} payNo={}", logNo, payment.getPayNo(), e);
            return false;
        }
    }

    /**
     * 解析对账事实支付单：先按缴费单存档 payNo 查；该单已 FAIL/CLOSED（P2-5 多次支付的
     * 墓碑终态）或查不到时，按 orderNo 回查当前活跃支付单——用户重新支付的新成功单挂在
     * 新 payNo 上，只摸旧死单会让补账永久失联。
     *
     * @return 应以之判定的支付单；无成功可能（无活跃单/响应空）返回 null；Feign 故障由
     *         调用方按异常处理（本方法不吞）
     */
    private PaymentDTO resolvePayment(String logNo, String payNo) {
        Result<PaymentDTO> result = payClient.getByPayNo(payNo);
        PaymentDTO payment = (result != null && result.isSuccess()) ? result.getData() : null;
        if (payment != null
                && !Objects.equals(payment.getStatus(), PayStatuses.FAIL.getCode())
                && !Objects.equals(payment.getStatus(), PayStatuses.CLOSED.getCode())) {
            return payment;
        }
        // 存档单已死（或异常缺失）：回查同 orderNo 的当前活跃支付单
        Result<PaymentDTO> activeResult = payClient.getActiveByOrderNo(logNo);
        if (activeResult == null || !activeResult.isSuccess()) {
            // 支付域明确无活跃单是正常结果（data=null）；Result 本身失败属异常，按故障重试
            if (activeResult != null) {
                return null;
            }
            log.warn("保证金到账对账：支付域未返回支付单，下轮重试 logNo={} payNo={}", logNo, payNo);
            throw new RuntimeException("支付域活跃支付单查询返回空响应");
        }
        return activeResult.getData();
    }
}
