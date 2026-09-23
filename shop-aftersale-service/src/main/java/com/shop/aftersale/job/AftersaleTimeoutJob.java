package com.shop.aftersale.job;

import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.aftersale.aftersale.mapper.AftersaleOrderMapper;
import com.shop.aftersale.aftersale.service.AftersaleTimeoutService;
import com.shop.aftersale.support.AftersaleDelayTopics;
import com.shop.aftersale.support.AftersaleTimeoutMessage;import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家审核/收货/换货发货超时扫描（MQ 延时消息的双保险）。
 */
@Component
@RequiredArgsConstructor
public class AftersaleTimeoutJob {

    private static final int LIMIT = 200;

    private final AftersaleOrderMapper orderMapper;
    private final AftersaleTimeoutService timeoutService;

    /** 仅退款/退货退款/换货：2 天未审核自动同意。 */
    @Scheduled(fixedDelay = 60_000L)
    @SchedulerLock(name = "aftersaleAuditTimeout", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void auditTimeout() {
        List<AftersaleOrder> list = orderMapper.selectAuditTimeout(10, LocalDateTime.now(), LIMIT);
        for (AftersaleOrder o : list) {
            // R4-25：轮次键取该轮审核截止点，与登记延时消息同键（重提轮次不会被首轮流水吞掉）
            timeoutService.dispatchTracked(AftersaleTimeoutMessage.forAftersale(
                    o.getAftersaleNo(), AftersaleDelayTopics.KIND_AUDIT,
                    AftersaleTimeoutMessage.deadlineRoundKey(o.getAuditDeadline())));
        }
    }

    /** 用户寄回后商家 3 天未确认收货：自动确认并退款。 */
    @Scheduled(fixedDelay = 60_000L)
    @SchedulerLock(name = "aftersaleReceiveTimeout", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void receiveTimeout() {
        List<AftersaleOrder> list = orderMapper.selectReceiveTimeout(LocalDateTime.now(), LIMIT);
        for (AftersaleOrder o : list) {
            timeoutService.dispatchTracked(AftersaleTimeoutMessage.forAftersale(
                    o.getAftersaleNo(), AftersaleDelayTopics.KIND_RECEIVE,
                    AftersaleTimeoutMessage.deadlineRoundKey(o.getReceiveDeadline())));
        }
    }

    /** 换货商家收货后 5 天未发货：自动转退款。 */
    @Scheduled(fixedDelay = 60_000L)
    @SchedulerLock(name = "aftersaleExchangeShipTimeout", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void exchangeShipTimeout() {
        List<AftersaleOrder> list = orderMapper.selectExchangeShipTimeout(LocalDateTime.now(), LIMIT);
        for (AftersaleOrder o : list) {
            timeoutService.dispatchTracked(AftersaleTimeoutMessage.forAftersale(
                    o.getAftersaleNo(), AftersaleDelayTopics.KIND_EXCHANGE_SHIP,
                    AftersaleTimeoutMessage.deadlineRoundKey(o.getExchangeShipDeadline())));
        }
    }
}
