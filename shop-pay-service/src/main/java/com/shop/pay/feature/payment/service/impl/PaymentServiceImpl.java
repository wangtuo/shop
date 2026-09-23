package com.shop.pay.feature.payment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.pay.dto.CreatePaymentCommand;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.pay.enums.PayMethods;
import com.shop.api.pay.enums.PayScenes;
import com.shop.api.pay.enums.PayStatuses;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.enums.OrderStatuses;
import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.AmountCommand;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.MoneyUtils;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.feign.FeignResults;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.framework.web.LoginUser;
import com.shop.pay.channel.ChannelLimits;
import com.shop.pay.channel.ChannelPayRequest;
import com.shop.pay.channel.ChannelPayResult;
import com.shop.pay.channel.ChannelQueryResult;
import com.shop.pay.channel.ChannelRouter;
import com.shop.pay.channel.PayChannelClient;
import com.shop.pay.feature.payment.dto.ChannelNotifyParams;
import com.shop.pay.feature.payment.dto.PayCreateRequest;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import com.shop.pay.feature.payment.entity.NotifyLog;
import com.shop.pay.feature.payment.entity.Payment;
import com.shop.pay.feature.payment.enums.PayScene;
import com.shop.pay.feature.payment.mapper.ChannelFlowMapper;
import com.shop.pay.feature.payment.mapper.NotifyLogMapper;
import com.shop.pay.feature.payment.mapper.PaymentMapper;
import com.shop.pay.feature.payment.service.PaymentService;
import com.shop.pay.feature.payment.statemachine.PaymentStateMachine;
import com.shop.pay.feature.payment.support.SignVerifier;
import com.shop.pay.mq.message.PayCheckMessage;
import com.shop.pay.support.PayAssembler;
import com.shop.pay.support.PayNoGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 支付单领域服务实现（design 6.2/6.3）。
 */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired(required = false))
public class PaymentServiceImpl implements PaymentService {

    // O6 业务指标（命名冻结于 GAP_PLAN_OBSERVABILITY）
    private static final String PAY_TOTAL = "shop_pay_total";
    private static final String PAY_FAILED_TOTAL = "shop_pay_failed_total";
    private static final String PAY_SECONDS = "shop_pay_seconds";

    /** 支付单超时时间（分钟） */
    public static final int EXPIRE_MINUTES = 30;

    /** B10：保证金缴费支付单固定主题（结算域经内部命令发起，忽略入参 subject）。 */
    public static final String DEPOSIT_SUBJECT = "保证金缴费";

    private final PaymentMapper paymentMapper;
    private final ChannelFlowMapper channelFlowMapper;
    private final NotifyLogMapper notifyLogMapper;
    private final PayNoGenerator payNoGenerator;
    private final ChannelRouter channelRouter;
    private final SignVerifier signVerifier;
    private final PaymentStateMachine stateMachine;
    private final DistributedLockTemplate lockTemplate;
    // P1-1：支付状态事件全部走 transactional outbox（与支付单状态同事务提交/回滚）
    private final OutboxPublisher outboxPublisher;
    private final UserClient userClient;
    private final OrderClient orderClient;
    // O6：无注册表环境（部分单测）为空，全部埋点空转安全
    private final MeterRegistry meterRegistry;

    // ------------------------------------------------------------------
    // 创建支付单
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentDTO createPayment(CreatePaymentCommand command) {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo(command.getOrderNo());
        request.setUserId(command.getUserId());
        request.setPayMethod(command.getPayMethod());
        request.setAmountFen(command.getAmountFen());
        request.setTerminal(command.getTerminal());
        // B10：保证金缴费（payScene=4）由结算域服务端构造命令，subject 固定"保证金缴费"，
        // order_no 为 DP 流水号（uk_order_no 天然幂等），墓碑槽 uk_order_active 与普通单同一套断网重试逻辑
        if (command.getPayScene() != null && command.getPayScene() == PayScenes.DEPOSIT) {
            request.setPayScene(PayScenes.DEPOSIT);
            request.setSubject(DEPOSIT_SUBJECT);
        } else {
            request.setSubject(command.getSubject());
        }
        // 内部 Feign 入参由订单域在服务端构造，保持既有契约校验（userId/金额非空、组合金额合计相等），
        // 不再经订单反查；C 端 /pays 入口的强制反查见 createPayment(PayCreateRequest)。
        validateInternalRequest(request);
        return lockAndCreate(request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentDTO createPayment(PayCreateRequest request) {
        // C-3：强制登录 + userId 只信网关身份（控制器已用 X-User-Id 覆盖请求体，这里二次兜底）
        if (request == null || request.getUserId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        // B10：payScene（含 4 保证金）仅服务端内部命令可指定；C 端请求体一律不信，场景由支付形态推导
        request.setPayScene(null);
        if (request.getOrderNo() == null || request.getOrderNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单号不能为空");
        }
        // C-3：按 orderNo 反查订单，校验归属；金额一律取订单应付金额，忽略请求体 amountFen
        OrderDTO order = FeignResults.unwrap(orderClient.getByOrderNo(request.getOrderNo()));
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在");
        }
        if (!request.getUserId().equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权为该订单发起支付");
        }
        // 订单状态只信订单域（不在 pay 库重建订单状态）：仅"待付款(10)"允许发起/重新发起支付。
        // 已取消（50，含 design 5.3.3 超时自动取消）或已支付后状态（20+）一律挡住，
        // 避免订单取消后新支付尝试扣款成功而订单域 10→20 CAS 无法落单（资金悬挂）。
        if (order.getStatus() == null || order.getStatus() != OrderStatuses.WAIT_PAY) {
            throw new BizException(ErrorCode.ORDER_STATUS_ERROR, "订单当前状态不允许发起支付");
        }
        Long payableFen = order.getPayFen();
        if (payableFen == null || payableFen <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单应付金额异常");
        }
        request.setAmountFen(payableFen);
        List<PayCreateRequest.PayPart> parts = normalizeParts(request);
        long sum = MoneyUtils.sum(parts.stream().map(PayCreateRequest.PayPart::getAmountFen).toList());
        if (sum != payableFen) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组合支付各手段金额合计必须等于订单应付金额");
        }
        return lockAndCreate(request);
    }

    private PaymentDTO lockAndCreate(PayCreateRequest request) {
        return lockTemplate.execute("pay:lock:create:" + request.getOrderNo(), () -> {
            // O6：支付主调用耗时（含渠道下单等待），成功/失败路径都记录
            long startNanos = System.nanoTime();
            String channelTag = channelTag(request);
            try {
                Payment existed = findByOrderNo(request.getOrderNo());
                if (existed != null) {
                    int oldStatus = existed.getStatus();
                    if (oldStatus == PayStatuses.FAIL.getCode() || oldStatus == PayStatuses.CLOSED.getCode()) {
                        // 重新支付（P2-5）：渠道支付失败/关单/延时核查置关闭的旧单为终态死单。
                        // 同事务先做墓碑槽位 CAS（active_slot 0 → 旧单自身 id，限定终态行），
                        // 更新 1 行才腾出 (order_no,0) 唯一槽位并继续 insert 新 WAIT 单；
                        // 0 行说明槽位/状态被并发抢占（理论上分布式锁内不会发生），重读活跃行幂等返回，
                        // 绝不带着 active_slot=0 强行 insert（uk_order_active 会以重复键回滚整个事务）。
                        if (paymentMapper.retireActiveSlot(existed.getId()) == 0) {
                            Payment current = findByOrderNo(request.getOrderNo());
                            if (current != null) {
                                return PayAssembler.toPaymentDTO(current);
                            }
                            throw new BizException(ErrorCode.CONFLICT, "支付单状态并发冲突，请刷新后重试");
                        }
                        // 落入下方新建分支：新 payNo + 新渠道流水 + 新 PAY_RESULT 延时核查 outbox
                    } else {
                        // WAIT/PAYING：幂等返回旧单（即使 expireTime 已过也不在此直接开新单——
                        // 过期等待单仍可能收到渠道迟到的成功回调/被用户线下付款完成，直接另开新单有
                        // 重复扣款风险；必须由 scanTimeout 主动核查渠道后 markClosed 置 50，下一次发起
                        // 才会走上面的终态入槽 + 新建）。
                        // SUCCESS/REFUNDING/REFUNDED：已支付成功，绝不新建，幂等返回旧单。
                        return PayAssembler.toPaymentDTO(existed);
                    }
                }
                List<PayCreateRequest.PayPart> parts = normalizeParts(request);
                Payment payment = buildPayment(request, parts);
                paymentMapper.insert(payment);

                List<ChannelFlow> flows = createFlows(payment, parts);
                if (isAllBalance(parts)) {
                    // 余额支付：内部实时扣减，直接成功并发事件
                    debitBalance(payment, flows);
                    Payment paid = findByPayNo(payment.getPayNo());
                    boolean advanced = completeSuccess(paid, channelFlowMapper.selectByPayNo(payment.getPayNo()),
                            "BALANCE_TXN_" + payment.getPayNo(), "BALANCE_DEBIT", LocalDateTime.now());
                    // 仅条件更新获胜者登记 outbox（与状态变更同事务），杜绝重复 ORDER_PAID（P1-2）
                    if (advanced) {
                        publishPaid(findByPayNo(payment.getPayNo()));
                    }
                } else {
                    // 组合支付中的余额部分：下单即内部实时扣减（design 6.2），余额流水置成功；
                    // 在线渠道部分仍等待异步回调。若在线部分最终失败/超时关单，
                    // releaseCapturedBalance 会把已扣余额原路退回，避免资金悬挂。
                    List<ChannelFlow> balanceFlows = balanceFlows(flows);
                    if (!balanceFlows.isEmpty()) {
                        debitBalanceFlows(payment, balanceFlows,
                                MoneyUtils.sum(balanceFlows.stream().map(ChannelFlow::getAmountFen).toList()),
                                "组合支付余额扣减 ");
                    }
                    // 第三方渠道：投递延时查询消息（超时双保险之一）。P1-1：outbox 延时事件，与支付单同事务
                    outboxPublisher.publishDelay(MqTopics.PAY_RESULT, "check",
                            new PayCheckMessage(payment.getPayNo(), payment.getOrderNo()),
                            payment.getPayNo(), MqTopics.DELAY_15_MIN_SECONDS);
                }
                return PayAssembler.toPaymentDTO(findByPayNo(payment.getPayNo()));
            } finally {
                recordPayTimer(channelTag, startNanos);
            }
        });
    }

    /** 内部 Feign 入参校验：userId/金额非空、组合金额合计等于总额（金额以命令为准，由订单域服务端构造）。 */
    private void validateInternalRequest(PayCreateRequest request) {
        if (request == null || request.getOrderNo() == null || request.getOrderNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单号不能为空");
        }
        if (request.getUserId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户ID不能为空");
        }
        if (request.getAmountFen() == null || request.getAmountFen() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "支付金额必须大于0");
        }
        List<PayCreateRequest.PayPart> parts = normalizeParts(request);
        long sum = MoneyUtils.sum(parts.stream().map(PayCreateRequest.PayPart::getAmountFen).toList());
        if (sum != request.getAmountFen()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组合支付各手段金额合计必须等于支付总额");
        }
    }

    private List<PayCreateRequest.PayPart> normalizeParts(PayCreateRequest request) {
        if (request.getParts() != null && !request.getParts().isEmpty()) {
            for (PayCreateRequest.PayPart part : request.getParts()) {
                if (part.getPayMethod() == null || part.getAmountFen() == null || part.getAmountFen() <= 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "组合支付明细非法");
                }
                PayMethods method = PayMethods.of(part.getPayMethod());
                ChannelLimits.check(method, part.getAmountFen(), request.getTerminal());
            }
            return request.getParts();
        }
        if (request.getPayMethod() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "支付方式不能为空");
        }
        PayMethods method = PayMethods.of(request.getPayMethod());
        ChannelLimits.check(method, request.getAmountFen(), request.getTerminal());
        PayCreateRequest.PayPart part = new PayCreateRequest.PayPart();
        part.setPayMethod(method.getCode());
        part.setAmountFen(request.getAmountFen());
        return List.of(part);
    }

    private Payment buildPayment(PayCreateRequest request, List<PayCreateRequest.PayPart> parts) {
        PayMethods mainMethod = PayMethods.of(parts.get(0).getPayMethod());
        // B10：内部命令显式场景（4 保证金）优先；C 端入口已清空 payScene，仍按支付形态推导
        int scene = request.getPayScene() != null ? request.getPayScene()
                : (parts.size() > 1 ? PayScene.COMPOSITE.getCode()
                : (request.getFriendUserId() != null ? PayScene.FRIEND_PAY.getCode() : PayScene.NORMAL.getCode()));
        Payment payment = new Payment();
        payment.setPayNo(payNoGenerator.payNo());
        payment.setOrderNo(request.getOrderNo());
        // 新尝试恒占活跃槽位 0（旧终态行已在 lockAndCreate 内 CAS 入墓碑槽位）
        payment.setActiveSlot(0L);
        payment.setUserId(request.getUserId());
        payment.setFriendUserId(request.getFriendUserId());
        payment.setPayMethod(mainMethod.getCode());
        payment.setPayScene(scene);
        payment.setTerminal(request.getTerminal());
        payment.setAmountFen(request.getAmountFen());
        payment.setRefundedFen(0L);
        payment.setSubject(request.getSubject());
        payment.setStatus(PayStatuses.WAIT.getCode());
        payment.setExpireTime(LocalDateTime.now().plusMinutes(EXPIRE_MINUTES));
        if (parts.size() == 1) {
            payment.setChannelCode(ChannelLimits.channelCode(mainMethod));
        }
        return payment;
    }

    private List<ChannelFlow> createFlows(Payment payment, List<PayCreateRequest.PayPart> parts) {
        List<ChannelFlow> flows = new ArrayList<>(parts.size());
        for (PayCreateRequest.PayPart part : parts) {
            PayMethods method = PayMethods.of(part.getPayMethod());
            String channelCode = ChannelLimits.channelCode(method);

            ChannelFlow flow = new ChannelFlow();
            flow.setPayNo(payment.getPayNo());
            flow.setOrderNo(payment.getOrderNo());
            flow.setPayMethod(method.getCode());
            flow.setChannelCode(channelCode);
            flow.setAmountFen(part.getAmountFen());
            flow.setFlowStatus(PayStatuses.WAIT.getCode());
            flow.setPaidFen(0L);

            if (!ChannelLimits.isBalance(channelCode)) {
                PayChannelClient client = channelRouter.route(channelCode);
                ChannelPayResult result = client.createOrder(ChannelPayRequest.builder()
                        .channelCode(channelCode)
                        .payNo(payment.getPayNo())
                        .orderNo(payment.getOrderNo())
                        .amountFen(part.getAmountFen())
                        .subject(payment.getSubject())
                        .terminal(payment.getTerminal())
                        .build());
                if (!result.isAccepted()) {
                    throw new BizException(ErrorCode.PAY_ERROR,
                            "渠道下单失败: " + channelCode + " " + result.getFailReason());
                }
                flow.setChannelOrderNo(result.getChannelOrderNo());
                flow.setPayUrl(result.getPayUrl());
                flow.setResponseBody(result.getPayUrl());
                if (parts.size() == 1) {
                    payment.setChannelOrderNo(result.getChannelOrderNo());
                    payment.setPayUrl(result.getPayUrl());
                }
            }
            channelFlowMapper.insert(flow);
            flows.add(flow);
        }
        return flows;
    }

    private void debitBalance(Payment payment, List<ChannelFlow> flows) {
        debitBalanceFlows(payment, flows, payment.getAmountFen(), "余额支付 ");
    }

    /**
     * 实时扣减余额并把对应余额流水置成功。bizNo=payNo，用户域按 bizNo 幂等，
     * 支付单重建/重试不会重复扣减。扣减失败抛 PAY_ERROR，支付创建事务整体回滚。
     */
    private void debitBalanceFlows(Payment payment, List<ChannelFlow> balanceFlows,
                                   long amountFen, String remarkPrefix) {
        try {
            FeignResults.unwrap(userClient.debitBalance(AmountCommand.builder()
                    .userId(payment.getUserId())
                    .bizNo(payment.getPayNo())
                    .amountFen(amountFen)
                    .remark(remarkPrefix + payment.getOrderNo())
                    .build()));
        } catch (BizException e) {
            // 余额不足等：支付失败（事务回滚，支付单不落库，由订单侧按失败处理/重试）
            throw new BizException(ErrorCode.PAY_ERROR, "余额支付扣减失败: " + e.getMessage(), e);
        }
        LocalDateTime now = LocalDateTime.now();
        for (ChannelFlow flow : balanceFlows) {
            channelFlowMapper.markSuccess(flow.getId(), "BALANCE_TXN_" + payment.getPayNo(), now);
        }
    }

    /**
     * 混合支付在线渠道最终失败/关单时，把下单时已实时扣减的余额部分退回余额账户
     * （design 6.4.1 余额退回余额账户）。bizNo=BALANCE_RELEASE_{payNo}，用户域流水按
     * bizNo 幂等，回调重放/超时任务重试不会重复入账。纯在线支付无已成功余额流水，no-op。
     */
    private void releaseCapturedBalance(Payment payment, List<ChannelFlow> flows) {
        if (flows == null || flows.isEmpty()) {
            return;
        }
        long captured = flows.stream()
                .filter(f -> ChannelLimits.isBalance(f.getChannelCode()))
                .filter(f -> f.getFlowStatus() != null
                        && f.getFlowStatus() == PayStatuses.SUCCESS.getCode())
                .mapToLong(f -> f.getAmountFen() == null ? 0L : f.getAmountFen())
                .sum();
        if (captured <= 0) {
            return;
        }
        FeignResults.unwrap(userClient.creditBalance(AmountCommand.builder()
                .userId(payment.getUserId())
                .bizNo("BALANCE_RELEASE_" + payment.getPayNo())
                .amountFen(captured)
                .remark("混合支付在线渠道未成功，释放余额 " + payment.getOrderNo())
                .build()));
    }

    private List<ChannelFlow> balanceFlows(List<ChannelFlow> flows) {
        return flows.stream()
                .filter(f -> ChannelLimits.isBalance(f.getChannelCode()))
                .toList();
    }

    // ------------------------------------------------------------------
    // 渠道回调
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentDTO handleNotify(ChannelNotifyParams params) {
        if (params.getNotifyType() == null) {
            params.setNotifyType(1);
        }
        // 1) 验签：失败记录回调流水并以 60002 拒绝
        try {
            signVerifier.verify(params);
        } catch (BizException e) {
            recordSignFailedNotify(params, e.getMessage());
            throw e;
        }

        // 2) 回调幂等表
        boolean first = recordNotifyReceived(params);
        if (!first) {
            NotifyLog existed = findNotify(params.getChannelCode(), params.getNotifyId());
            if (existed != null && existed.getHandleStatus() == 1) {
                // 已成功处理：重复回调幂等返回
                return PayAssembler.toPaymentDTO(findByPayNo(params.getPayNo()));
            }
            if (existed != null && existed.getHandleStatus() == 2) {
                throw new BizException(ErrorCode.PAY_SIGN_ERROR, "回调验签失败 notifyId=" + params.getNotifyId());
            }
        }

        // 3) 支付单存在性 / 金额 / 状态校验
        Payment payment = findByPayNo(params.getPayNo());
        if (payment == null) {
            markNotifyFail(params, "支付单不存在");
            throw new BizException(ErrorCode.NOT_FOUND, "支付单不存在");
        }
        if (!"SUCCESS".equalsIgnoreCase(params.getStatus())) {
            if (payment.getStatus() == PayStatuses.WAIT.getCode()
                    || payment.getStatus() == PayStatuses.PAYING.getCode()) {
                if (paymentMapper.markFail(payment.getPayNo(), "渠道回调支付失败") > 0) {
                    // O6：渠道等待后回调明确失败
                    recordPayResult(payment, false);
                    // 组合支付下单时已扣的余额随失败实时退回（bizNo 幂等，重放不重复入账）
                    releaseCapturedBalance(payment,
                            channelFlowMapper.selectByPayNo(payment.getPayNo()));
                }
            }
            markNotifyDone(params);
            return PayAssembler.toPaymentDTO(findByPayNo(payment.getPayNo()));
        }
        if (params.getAmountFen() == null || !params.getAmountFen().equals(payment.getAmountFen())) {
            markNotifyFail(params, "回调金额与支付单金额不符");
            throw new BizException(ErrorCode.PAY_ERROR,
                    "回调金额与支付单金额不符: callback=" + params.getAmountFen()
                            + " payment=" + payment.getAmountFen());
        }
        if (payment.getStatus() == PayStatuses.SUCCESS.getCode()
                || payment.getStatus() == PayStatuses.REFUNDING.getCode()
                || payment.getStatus() == PayStatuses.REFUNDED.getCode()) {
            // 已成功：幂等 ACK
            markNotifyDone(params);
            return PayAssembler.toPaymentDTO(payment);
        }
        if (payment.getStatus() != PayStatuses.WAIT.getCode()
                && payment.getStatus() != PayStatuses.PAYING.getCode()) {
            markNotifyFail(params, "支付单状态不允许成功回调");
            throw new BizException(ErrorCode.CONFLICT, "支付单状态不允许成功回调");
        }

        // 4) 条件更新落单（10/20 → 30），影响 0 行即并发冲突
        LocalDateTime paidTime = params.getPaidTime() == null ? LocalDateTime.now() : params.getPaidTime();
        boolean advanced = completeSuccess(payment, channelFlowMapper.selectByPayNo(payment.getPayNo()),
                params.getChannelTxnNo(), params.getNotifyId(), paidTime);
        // 不同 notifyId 的重复通知也要 ACK（渠道重发属正常行为，P2-2），避免渠道持续重试
        markNotifyDone(params);

        // 5) 支付域只发事件，跨域状态变更由各域幂等消费。
        // P1-2：仅条件更新的获胜线程登记 outbox；并发重复回调（不同 notifyId / 回调+主动查询）
        // 在 completeSuccess 内 CAS 落败时不再重复发布 ORDER_PAID/PAY_RESULT。
        if (advanced) {
            publishPaid(findByPayNo(payment.getPayNo()));
        }
        return PayAssembler.toPaymentDTO(findByPayNo(payment.getPayNo()));
    }

    /**
     * 条件更新推进支付单为成功。
     *
     * @return true 仅当本次调用真正完成 10/20 → 30（CAS 影响行数 &gt; 0）；已被并发回调/主动查询
     *         抢先处理时返回 false，调用方不得再发布支付成功事件（P1-2）。
     */
    private boolean completeSuccess(Payment payment, List<ChannelFlow> flows,
                                    String channelTxnNo, String notifyId, LocalDateTime paidTime) {
        stateMachine.assertTransition(payment.getStatus(), PayStatuses.SUCCESS.getCode());
        int rows = paymentMapper.markSuccess(payment.getPayNo(), channelTxnNo, notifyId, paidTime);
        if (rows == 0) {
            // 已被并发回调/主动查询处理：幂等返回，由获胜方负责发布事件
            Payment latest = findByPayNo(payment.getPayNo());
            if (latest != null && latest.getStatus() == PayStatuses.SUCCESS.getCode()) {
                return false;
            }
            throw new BizException(ErrorCode.CONFLICT, "支付单状态并发冲突");
        }
        payment.setStatus(PayStatuses.SUCCESS.getCode());
        // O6：支付成功唯一收敛点（余额直付/渠道回调/主动查询三路均经此 CAS）
        recordPayResult(payment, true);
        if (flows != null) {
            for (ChannelFlow flow : flows) {
                if (flow.getFlowStatus() == PayStatuses.WAIT.getCode()
                        || flow.getFlowStatus() == PayStatuses.PAYING.getCode()) {
                    channelFlowMapper.markSuccess(flow.getId(),
                            channelTxnNo != null ? channelTxnNo : flow.getChannelTransactionNo(), paidTime);
                }
            }
        }
        return true;
    }

    /** P1-1：契约事件登记 outbox，必须由 {@code @Transactional} 方法在状态变更后同事务调用。 */
    private void publishPaid(Payment payment) {
        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .payNo(payment.getPayNo())
                .orderNo(payment.getOrderNo())
                .userId(payment.getUserId())
                .payMethod(payment.getPayMethod())
                .amountFen(payment.getAmountFen())
                .channelTransactionNo(payment.getChannelTransactionNo())
                .paidTime(payment.getPayTime())
                // C2：支付域生产事件从支付单回填 payScene（1 普通/2 组合/3 代付/4 保证金），消费侧按场景分流
                .payScene(payment.getPayScene())
                .build();
        event.setBizNo(payment.getPayNo());
        // 契约事件：ORDER_PAID 供订单/商品/营销/用户/清算消费
        outboxPublisher.publish(MqTopics.ORDER_PAID, "paid", event, payment.getPayNo());
        // 渠道回调原始结果
        outboxPublisher.publish(MqTopics.PAY_RESULT, "result", event, payment.getPayNo());
    }

    // ------------------------------------------------------------------
    // 主动查询（补偿双保险）
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentDTO activeQuery(String payNo) {
        Payment payment = findByPayNo(payNo);
        if (payment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "支付单不存在");
        }
        int status = payment.getStatus();
        if (status == PayStatuses.SUCCESS.getCode()
                || status == PayStatuses.REFUNDING.getCode()
                || status == PayStatuses.REFUNDED.getCode()
                || status == PayStatuses.FAIL.getCode()
                || status == PayStatuses.CLOSED.getCode()) {
            return PayAssembler.toPaymentDTO(payment);
        }
        List<ChannelFlow> flows = channelFlowMapper.selectByPayNo(payNo);
        boolean anySuccess = false;
        boolean anyFail = false;
        String txnNo = null;
        for (ChannelFlow flow : flows) {
            if (ChannelLimits.isBalance(flow.getChannelCode())) {
                continue;
            }
            if (flow.getFlowStatus() != PayStatuses.WAIT.getCode()
                    && flow.getFlowStatus() != PayStatuses.PAYING.getCode()) {
                continue;
            }
            ChannelQueryResult result = channelRouter.route(flow.getChannelCode())
                    .query(flow.getChannelCode(), flow.getChannelOrderNo());
            if (result.getState() == ChannelQueryResult.State.SUCCESS) {
                anySuccess = true;
                txnNo = result.getChannelTxnNo() != null ? result.getChannelTxnNo()
                        : flow.getChannelCode() + "_T_" + flow.getChannelOrderNo();
            } else if (result.getState() == ChannelQueryResult.State.FAIL
                    || result.getState() == ChannelQueryResult.State.CLOSED) {
                anyFail = true;
            }
        }
        if (anySuccess) {
            // 同样仅 CAS 获胜方登记 outbox，回调与主动查询并发时不重复发 ORDER_PAID（P1-2）
            boolean advanced = completeSuccess(payment, flows, txnNo, "QRY_" + payNo, LocalDateTime.now());
            if (advanced) {
                publishPaid(findByPayNo(payNo));
            }
        } else if (anyFail) {
            if (paymentMapper.markFail(payNo, "主动查询渠道返回失败") > 0) {
                // O6：主动查询确认渠道失败
                recordPayResult(payment, false);
                // 组合支付已扣余额随失败实时退回（bizNo 幂等）
                releaseCapturedBalance(payment, flows);
            }
        }
        return PayAssembler.toPaymentDTO(findByPayNo(payNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int scanTimeout(int limit) {
        List<Payment> timeouts = paymentMapper.selectTimeout(LocalDateTime.now(), limit);
        int handled = 0;
        for (Payment payment : timeouts) {
            // 先主动查询渠道，渠道确认未支付才关单
            PaymentDTO latest = activeQuery(payment.getPayNo());
            if (latest.getStatus() == PayStatuses.WAIT.getCode()
                    || latest.getStatus() == PayStatuses.PAYING.getCode()) {
                int rows = paymentMapper.markClosed(payment.getPayNo(), LocalDateTime.now());
                if (rows > 0) {
                    // O6：渠道等待超时关单计入支付失败
                    recordPayResult(payment, false);
                    // 组合支付超时关单：下单时已扣的余额实时退回（bizNo 幂等），在线流水置关闭
                    releaseCapturedBalance(payment,
                            channelFlowMapper.selectByPayNo(payment.getPayNo()));
                    channelFlowMapper.markClosedByPayNo(payment.getPayNo());
                    handled++;
                }
            } else {
                handled++;
            }
        }
        return handled;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public PaymentDTO getByPayNo(String payNo) {
        Payment payment = findByPayNo(payNo);
        if (payment == null) {
            // L-2：错误信息不回显内部单号
            throw new BizException(ErrorCode.NOT_FOUND, "支付单不存在");
        }
        return PayAssembler.toPaymentDTO(payment);
    }

    @Override
    public PaymentDTO getByOrderNo(String orderNo) {
        Payment payment = findByOrderNo(orderNo);
        if (payment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单无支付单");
        }
        return PayAssembler.toPaymentDTO(payment);
    }

    @Override
    public PaymentDTO findActiveByOrderNo(String orderNo) {
        Payment payment = findByOrderNo(orderNo);
        return payment == null ? null : PayAssembler.toPaymentDTO(payment);
    }

    @Override
    public PaymentDTO viewByPayNo(String payNo, LoginUser viewer) {
        Payment payment = findByPayNo(payNo);
        if (payment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "支付单不存在");
        }
        requireCanView(payment, viewer);
        return PayAssembler.toPaymentDTO(payment);
    }

    @Override
    public PaymentDTO viewByOrderNo(String orderNo, LoginUser viewer) {
        Payment payment = findByOrderNo(orderNo);
        if (payment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单无支付单");
        }
        requireCanView(payment, viewer);
        return PayAssembler.toPaymentDTO(payment);
    }

    /** C-3 查询归属：仅支付单买家本人或平台运营可查（商户端经订单/售后域内部接口查询）。 */
    private void requireCanView(Payment payment, LoginUser viewer) {
        if (viewer == null || viewer.getUserId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (viewer.getUserType() != null && viewer.getUserType() == 2) {
            return;
        }
        if (!viewer.getUserId().equals(payment.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看该支付单");
        }
    }

    // ------------------------------------------------------------------
    // 回调流水
    // ------------------------------------------------------------------

    private boolean recordNotifyReceived(ChannelNotifyParams params) {
        NotifyLog log = new NotifyLog();
        log.setChannelCode(params.getChannelCode());
        log.setNotifyId(params.getNotifyId());
        log.setPayNo(params.getPayNo());
        log.setChannelTxnNo(params.getChannelTxnNo());
        log.setNotifyType(params.getNotifyType());
        log.setSignStatus(1);
        log.setHandleStatus(0);
        log.setNotifyBody(params.getSign());
        return notifyLogMapper.insertIgnore(log) > 0;
    }

    private void recordSignFailedNotify(ChannelNotifyParams params, String reason) {
        NotifyLog log = new NotifyLog();
        log.setChannelCode(params.getChannelCode());
        log.setNotifyId(params.getNotifyId());
        log.setPayNo(params.getPayNo());
        log.setChannelTxnNo(params.getChannelTxnNo());
        log.setNotifyType(params.getNotifyType() == null ? 1 : params.getNotifyType());
        log.setSignStatus(2);
        log.setHandleStatus(2);
        log.setNotifyBody(params.getSign());
        log.setFailReason(reason);
        notifyLogMapper.insertIgnore(log);
    }

    private void markNotifyDone(ChannelNotifyParams params) {
        NotifyLog log = findNotify(params.getChannelCode(), params.getNotifyId());
        if (log != null) {
            notifyLogMapper.updateResult(log.getId(), 1, 1, null);
        }
    }

    private void markNotifyFail(ChannelNotifyParams params, String reason) {
        NotifyLog log = findNotify(params.getChannelCode(), params.getNotifyId());
        if (log != null) {
            notifyLogMapper.updateResult(log.getId(), 1, 3, reason);
        }
    }

    private NotifyLog findNotify(String channelCode, String notifyId) {
        return notifyLogMapper.selectOne(new LambdaQueryWrapper<NotifyLog>()
                .eq(NotifyLog::getChannelCode, channelCode)
                .eq(NotifyLog::getNotifyId, notifyId));
    }

    private Payment findByPayNo(String payNo) {
        return paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPayNo, payNo));
    }

    /**
     * 按订单号取当前活跃支付单（active_slot=0）。多次支付尝试的历史终态行
     * （active_slot=自身 id）不命中；按 payNo 的查询（回调/主动查询/getByPayNo）不受影响。
     */
    private Payment findByOrderNo(String orderNo) {
        return paymentMapper.selectActiveByOrderNo(orderNo);
    }

    private boolean isAllBalance(List<PayCreateRequest.PayPart> parts) {
        return parts.size() == 1
                && ChannelLimits.isBalance(ChannelLimits.channelCode(PayMethods.of(parts.get(0).getPayMethod())));
    }

    // ------------------------------------------------------------------
    // O6 业务指标（shop_pay_total / shop_pay_failed_total / shop_pay_seconds）
    // ------------------------------------------------------------------

    private void recordPayResult(Payment payment, boolean success) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(success ? PAY_TOTAL : PAY_FAILED_TOTAL,
                "channel", channelTag(payment), "result", success ? "success" : "fail").increment();
    }

    private void recordPayTimer(String channelTag, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder(PAY_SECONDS)
                .tags("channel", channelTag)
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private String channelTag(Payment payment) {
        if (payment.getChannelCode() != null && !payment.getChannelCode().isBlank()) {
            return payment.getChannelCode();
        }
        return payment.getPayMethod() == null ? "unknown" : channelTag(payment.getPayMethod());
    }

    private String channelTag(PayCreateRequest request) {
        Integer code = request.getPayMethod();
        if (code == null && request.getParts() != null && !request.getParts().isEmpty()) {
            code = request.getParts().get(0).getPayMethod();
        }
        return code == null ? "unknown" : channelTag(code);
    }

    private String channelTag(int payMethodCode) {
        try {
            return ChannelLimits.channelCode(PayMethods.of(payMethodCode));
        } catch (Exception e) {
            return "unknown";
        }
    }
}
