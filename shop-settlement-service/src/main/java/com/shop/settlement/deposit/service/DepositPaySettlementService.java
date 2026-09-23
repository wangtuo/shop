package com.shop.settlement.deposit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.pay.enums.PayScenes;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.clearing.service.ShortfallWorkOrderService;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.deposit.mapper.DepositLogMapper;
import com.shop.settlement.enums.DepositLogTypes;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.mapper.MerchantMapper;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.mq.service.MqConsumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 保证金缴费到账入账（ORDER_PAID payScene=4 专属消费者，GAP_PLAN_FUNDS B10 步骤 1③）。
 *
 * <p>三重幂等：</p>
 * <ol>
 *   <li>t_sett_mq_consume 以 (eventId, consumerGroup) 登记（R4-24 复合唯一键，与业务同事务，
 *       回滚可重投；同事件扇出 cg_sett_paid/cg_sett_deposit_pay 两组各自独立幂等）；</li>
 *   <li>DP 日志 status CAS 10→20，并发重放只有获胜方记账；</li>
 *   <li>账户流水 UK(biz_no=logNo, change_type=40) 兜底。</li>
 * </ol>
 * 入账后触发 P0-1 自动补扣钩子 {@link ShortfallWorkOrderService#clawbackOnDepositPaid(long)}。
 * R4-24 另有 {@link com.shop.settlement.deposit.job.DepositPayRecoveryJob} 以支付域为事实源
 * 对账兜底 status=10 缴费单，杜绝到账事件静默丢失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositPaySettlementService {

    /** 消费组 cg_sett_deposit_pay（create-topics.sh 已登记） */
    public static final String CG_DEPOSIT_PAY = "cg_sett_deposit_pay";

    private final MqConsumeService mqConsumeService;
    private final DepositLogMapper depositLogMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantService merchantService;
    private final AccountService accountService;
    private final ShortfallWorkOrderService shortfallWorkOrderService;

    /**
     * 处理保证金缴费到账事件。
     *
     * @return true 本次调用真实完成入账（CAS 10→20 获胜）；false 被场景守卫/事件幂等/
     *         单据状态幂等拦截（他节点或他轮次已入账），调用方不得再计为补账成功。
     *         数据异常（缴费单缺失/金额不符/入账失败）仍抛异常由 MQ/对账轮重试。
     */
    @Transactional
    public boolean onPaymentSucceeded(PaymentSucceededEvent event) {
        // 仅处理保证金缴费场景；listener 首行亦有守卫，此处防御性再判
        if (event.getPayScene() == null || event.getPayScene() != PayScenes.DEPOSIT) {
            return false;
        }
        // ① eventId 幂等（bizNo=logNo=orderNo）
        if (!mqConsumeService.tryRecord(event.getEventId(), MqTopics.ORDER_PAID,
                CG_DEPOSIT_PAY, event.getOrderNo())) {
            return false;
        }
        // ② DP 日志定位：优先 payNo，兼容 payNo 回写前事件到达（支付侧 orderNo=logNo 强约束）
        SettDepositLog depositLog = depositLogMapper.selectByPayNo(event.getPayNo());
        if (depositLog == null) {
            depositLog = depositLogMapper.selectOne(new LambdaQueryWrapper<SettDepositLog>()
                    .eq(SettDepositLog::getLogNo, event.getOrderNo())
                    .eq(SettDepositLog::getLogType, DepositLogTypes.PAY)
                    .last("LIMIT 1"));
        }
        if (depositLog == null) {
            // 建单先于支付调用，正常必然存在；缺失属数据异常，抛错由 MQ 重试，禁止裸 ACK 长款
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "保证金缴费支付成功但 DP 日志不存在: " + event.getOrderNo());
        }
        if (event.getAmountFen() == null || !event.getAmountFen().equals(depositLog.getAmountFen())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "保证金到账金额与缴费单不一致 logNo=" + depositLog.getLogNo()
                            + " event=" + event.getAmountFen() + " log=" + depositLog.getAmountFen());
        }
        // ③ CAS 10→20：获胜方才记账，事件重放/并发零副作用
        if (depositLogMapper.casStatus(depositLog.getLogNo(),
                SettDepositLog.STATUS_PROCESSING, SettDepositLog.STATUS_SUCCESS) != 1) {
            log.info("保证金缴费单非待支付态，忽略到账事件 logNo={} status 已推进", depositLog.getLogNo());
            return false;
        }
        // ④ 保证金余额入账（先款后账：到账是唯一入账依据）
        int rows = merchantMapper.changeDeposit(depositLog.getMerchantId(), depositLog.getAmountFen());
        if (rows != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "保证金到账入账失败: " + depositLog.getLogNo());
        }
        SettMerchant latest = merchantService.requireMerchant(depositLog.getMerchantId());
        // 账户侧 40 流水留痕（bizNo=logNo，UK(biz_no,change_type) 幂等）；资金账以保证金余额为准
        accountService.writeZeroFlow(latest.getId(), AccountRole.MERCHANT,
                depositLog.getLogNo(), FlowChangeTypes.DEPOSIT_PAY, "保证金缴纳到账");
        // 补足 50% 阈值后解除预警标记
        if (latest.getDepositAlerted() != null && latest.getDepositAlerted() == 1) {
            boolean stillBelow = latest.getDepositRequiredFen() != null
                    && latest.getDepositBalanceFen() * 2 < latest.getDepositRequiredFen();
            if (!stillBelow) {
                latest.setDepositAlerted(0);
                merchantMapper.updateById(latest);
            }
        }
        log.info("保证金缴费到账入账成功 logNo={} merchantId={} amount={} payNo={}",
                depositLog.getLogNo(), latest.getId(), depositLog.getAmountFen(), event.getPayNo());
        // ⑤ 触发穿仓缺口工单自动补扣（P0-1 钩子，同事务）
        shortfallWorkOrderService.clawbackOnDepositPaid(latest.getId());
        return true;
    }
}
