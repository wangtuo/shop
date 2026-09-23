package com.shop.aftersale.aftersale.service.impl;

import com.shop.aftersale.aftersale.entity.AftersaleDispute;
import com.shop.aftersale.aftersale.entity.AftersaleInsurance;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.aftersale.aftersale.enums.AftersaleCodes;
import com.shop.aftersale.aftersale.mapper.AftersaleDisputeMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleInsuranceMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleOrderMapper;
import com.shop.aftersale.aftersale.service.AftersaleService;
import com.shop.aftersale.aftersale.service.AftersaleTimeoutService;
import com.shop.aftersale.mq.mapper.MqConsumeLogMapper;
import com.shop.aftersale.support.AftersaleDelayTopics;
import com.shop.aftersale.support.AftersalePolicy;
import com.shop.aftersale.support.AftersaleTimeoutMessage;
import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.AmountCommand;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.framework.mq.MqConsumeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 超时处理：MQ 延时消息与 @Scheduled 扫描双保险，全部走条件更新，天然幂等。
 */
@Service
public class AftersaleTimeoutServiceImpl implements AftersaleTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(AftersaleTimeoutServiceImpl.class);

    private final AftersaleService aftersaleService;
    private final AftersaleInsuranceMapper insuranceMapper;
    private final AftersaleDisputeMapper disputeMapper;
    private final AftersaleOrderMapper orderMapper;
    private final UserClient userClient;
    private final AftersalePolicy policy;
    // 自注入代理：dispatch 内调用本类 @Transactional 方法（claimInsurance/closeEvidence）
    // 必须经过代理，否则 this 调用绕过事务——claimInsurance 里 markClaimed 与 Feign
    // 理赔到账将不在同一事务内，失败无法回滚 CAS。@Lazy 打破构造期循环依赖。
    private final AftersaleTimeoutService self;
    private final MqConsumeLogMapper mqConsumeLogMapper;

    public AftersaleTimeoutServiceImpl(AftersaleService aftersaleService,
                                       AftersaleInsuranceMapper insuranceMapper,
                                       AftersaleDisputeMapper disputeMapper,
                                       AftersaleOrderMapper orderMapper,
                                       UserClient userClient,
                                       AftersalePolicy policy,
                                       @Lazy AftersaleTimeoutService self,
                                       MqConsumeLogMapper mqConsumeLogMapper) {
        this.aftersaleService = aftersaleService;
        this.insuranceMapper = insuranceMapper;
        this.disputeMapper = disputeMapper;
        this.orderMapper = orderMapper;
        this.userClient = userClient;
        this.policy = policy;
        this.self = self;
        this.mqConsumeLogMapper = mqConsumeLogMapper;
    }

    @Override
    public void dispatch(AftersaleTimeoutMessage msg) {
        if (msg == null || msg.getKind() == null) {
            return;
        }
        switch (msg.getKind()) {
            case AftersaleDelayTopics.KIND_AUDIT -> aftersaleService.autoApprove(msg.getAftersaleNo());
            case AftersaleDelayTopics.KIND_RECEIVE -> aftersaleService.autoConfirmReceive(msg.getAftersaleNo());
            case AftersaleDelayTopics.KIND_EXCHANGE_SHIP ->
                    aftersaleService.autoConvertExchangeToRefund(msg.getAftersaleNo());
            case AftersaleDelayTopics.KIND_EVIDENCE -> self.closeEvidence(msg.getAftersaleNo());
            case AftersaleDelayTopics.KIND_INSURANCE -> self.claimInsurance(msg.getInsuranceId());
            default -> throw new IllegalArgumentException("未知超时类型: " + msg.getKind());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispatchTracked(AftersaleTimeoutMessage msg) {
        if (msg == null || msg.getKind() == null) {
            return;
        }
        // R4-26：轮次新鲜度守卫。同一售后单可两次进入同一状态（拒绝后修改重提、仲裁后
        // 重回待换货发货），旧轮延时消息可能在第二轮窗口内才被 broker 投递（broker 重投/
        // 消费积压/消费者停机后追赶）。轮次键随各轮截止点不同，消息轮次与当前轮次不一致
        // 即旧轮迟到消息——直接 ACK 丢弃：若落库执行，第二轮会被提前最多一个审核/收货
        // 时限自动流转（买家未获完整审核窗口，甚至系统自动同意/退款），属时效正确性缺陷。
        // 仅状态机幂等挡不住：第二轮恰好处于守卫状态时旧动作会被真实执行。
        if (!isCurrentRound(msg)) {
            log.warn("旧轮超时消息迟到，按当前轮次丢弃 no={} kind={} msgRound={}",
                    msg.getAftersaleNo(), msg.getKind(), msg.getRoundKey());
            return;
        }
        String eventId = resolveEventId(msg);
        // 消费流水与业务动作同事务：业务抛异常（含 R-B6 DEPENDENCY_FAIL）时流水随事务回滚，
        // broker 可重投恢复；成功则同提交，重投/扫表被 UK event_id 拦截。
        if (mqConsumeLogMapper.insertIgnore(eventId,
                AftersaleDelayTopics.AFTERSALE_TIMEOUT, msg.getBizNo()) == 0) {
            return;
        }
        dispatch(msg);
    }

    /**
     * R4-26 轮次新鲜度校验：消息轮次必须等于售后单当前轮次（按当前 deadline 现算）。
     *
     * <p>仅 audit/receive/exchange_ship 有轮次维度（重提/仲裁会重新进入同一状态）；
     * insurance（每单一次）、evidence（单次举证）不校验。消息无轮次键（R4-25 前登记的
     * 历史存量延时行）时无法判别轮次，维持既有状态机守卫语义放行；当前订单查不到也放行，
     * 交由业务 requireAftersale 的既有异常/重试路径处理。当前 deadline 已不存在而消息带
     * 轮次键，必然是旧轮消息，丢弃。
     */
    private boolean isCurrentRound(AftersaleTimeoutMessage msg) {
        if (!StringUtils.hasText(msg.getRoundKey())) {
            return true;
        }
        String kind = msg.getKind();
        boolean roundScoped = AftersaleDelayTopics.KIND_AUDIT.equals(kind)
                || AftersaleDelayTopics.KIND_RECEIVE.equals(kind)
                || AftersaleDelayTopics.KIND_EXCHANGE_SHIP.equals(kind);
        if (!roundScoped) {
            return true;
        }
        AftersaleOrder o = orderMapper.selectByNo(msg.getAftersaleNo());
        if (o == null) {
            return true;
        }
        LocalDateTime deadline = switch (kind) {
            case AftersaleDelayTopics.KIND_AUDIT -> o.getAuditDeadline();
            case AftersaleDelayTopics.KIND_RECEIVE -> o.getReceiveDeadline();
            default -> o.getExchangeShipDeadline();
        };
        return Objects.equals(AftersaleTimeoutMessage.deadlineRoundKey(deadline),
                msg.getRoundKey());
    }

    /**
     * 归一化确定性 eventId：消息自带（工厂产出或框架已回填）则沿用；空白、或框架标记为
     * 合成（历史 outbox 存量无信封消息）时按业务键现算，保证 MQ 与扫表命中同一流水行。
     * R4-25：现算必须带轮次键，否则同单第二轮超时会被首轮流水行永久吞掉。
     */
    private String resolveEventId(AftersaleTimeoutMessage msg) {
        MqConsumeContext ctx = MqConsumeContext.current();
        String eventId = msg.getEventId();
        if (!StringUtils.hasText(eventId) || (ctx != null && ctx.isSynthetic())) {
            eventId = AftersaleTimeoutMessage.deriveId(
                    msg.getAftersaleNo(), msg.getKind(), msg.getInsuranceId(), msg.getRoundKey());
            msg.setEventId(eventId);
        }
        if (!StringUtils.hasText(msg.getBizNo())) {
            msg.setBizNo(msg.getAftersaleNo());
        }
        return eventId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claimInsurance(Long insuranceId) {
        if (insuranceId == null) {
            return;
        }
        AftersaleInsurance ins = insuranceMapper.selectById(insuranceId);
        if (ins == null || ins.getStatus() != AftersaleCodes.INSURANCE_WAIT) {
            return;
        }
        LocalDateTime now = policy.now();
        if (now.isBefore(ins.getClaimDeadline())) {
            return;
        }
        if (insuranceMapper.markClaimed(insuranceId, now) == 0) {
            return;
        }
        Result<Void> r = userClient.creditBalance(AmountCommand.builder()
                .userId(ins.getUserId())
                .bizNo("INS:" + ins.getOrderNo())
                .amountFen(ins.getClaimFen())
                .remark("退货运费险理赔")
                .build());
        // null 必须按失败处理：ShopErrorDecoder 已保证 Feign 框架永不返回 null，
        // 此判空是业务侧防回退第三道防线。抛错后本事务回滚（markClaimed + 消费流水一并
        // 回滚），MQ/60s 扫表重试；DEPENDENCY_FAIL 不在 MqErrorPolicy 终态集合，可恢复；
        // user 侧按 bizNo=INS:orderNo 幂等，重投安全。
        if (r == null || !r.isSuccess()) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                    "运费险理赔到账失败: " + (r == null ? "下游返回空响应" : r.getMessage()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeEvidence(String aftersaleNo) {
        AftersaleDispute d = disputeMapper.selectByAftersaleNo(aftersaleNo);
        if (d == null || d.getStatus() != AftersaleCodes.DISPUTE_EVIDENCING) {
            return;
        }
        if (policy.now().isBefore(d.getEvidenceDeadline())) {
            return;
        }
        disputeMapper.updateStatus(d.getId(), AftersaleCodes.DISPUTE_EVIDENCING,
                AftersaleCodes.DISPUTE_WAIT_ARBITRATE);
    }
}
