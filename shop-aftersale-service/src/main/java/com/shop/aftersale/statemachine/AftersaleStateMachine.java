package com.shop.aftersale.statemachine;

import com.shop.api.aftersale.enums.AftersaleStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 售后状态机（无状态）。非法跳转抛 AFTERSALE_STATUS_ERROR，全部流转须经此校验。
 *
 * <pre>
 * 10 待审核 → 20 待买家退货 / 40 退款中 / 41 待换货(补发)发货 / 55 已拒绝 / 80 介入 / 90 撤销
 * 20 → 30 商家收货中 / 80 / 90
 * 30 → 40 退款中 / 41 待换货发货 / 55 拒收 / 80 / 90
 * 40 → 50 已完成
 * 41 → 42 换货已发货 / 40 转退款（5 天超时）
 * 42 → 43 换货待收货 → 50
 * 55 → 10 修改重提 / 80 介入 / 90 撤销
 * 80 → 40 / 41 / 55（仲裁）
 * 50 / 90 终态
 * </pre>
 */
@Component
public class AftersaleStateMachine {

    private static final Map<Integer, Set<Integer>> EDGES = Map.ofEntries(
            Map.entry(AftersaleStatuses.WAIT_MERCHANT_AUDIT, Set.of(
                    AftersaleStatuses.WAIT_BUYER_RETURN,
                    AftersaleStatuses.REFUNDING,
                    AftersaleStatuses.WAIT_EXCHANGE_SHIP,
                    AftersaleStatuses.REJECTED,
                    AftersaleStatuses.PLATFORM_INTERVENING,
                    AftersaleStatuses.CANCELED)),
            Map.entry(AftersaleStatuses.WAIT_BUYER_RETURN, Set.of(
                    AftersaleStatuses.MERCHANT_RECEIVING,
                    AftersaleStatuses.PLATFORM_INTERVENING,
                    AftersaleStatuses.CANCELED)),
            Map.entry(AftersaleStatuses.MERCHANT_RECEIVING, Set.of(
                    AftersaleStatuses.REFUNDING,
                    AftersaleStatuses.WAIT_EXCHANGE_SHIP,
                    AftersaleStatuses.REJECTED,
                    AftersaleStatuses.PLATFORM_INTERVENING,
                    AftersaleStatuses.CANCELED)),
            Map.entry(AftersaleStatuses.REFUNDING, Set.of(AftersaleStatuses.FINISHED)),
            Map.entry(AftersaleStatuses.WAIT_EXCHANGE_SHIP, Set.of(
                    AftersaleStatuses.EXCHANGE_SHIPPED,
                    AftersaleStatuses.REFUNDING)),
            Map.entry(AftersaleStatuses.EXCHANGE_SHIPPED, Set.of(AftersaleStatuses.EXCHANGE_WAIT_RECEIVE)),
            Map.entry(AftersaleStatuses.EXCHANGE_WAIT_RECEIVE, Set.of(AftersaleStatuses.FINISHED)),
            Map.entry(AftersaleStatuses.REJECTED, Set.of(
                    AftersaleStatuses.WAIT_MERCHANT_AUDIT,
                    AftersaleStatuses.PLATFORM_INTERVENING,
                    AftersaleStatuses.CANCELED)),
            Map.entry(AftersaleStatuses.PLATFORM_INTERVENING, Set.of(
                    AftersaleStatuses.REFUNDING,
                    AftersaleStatuses.WAIT_EXCHANGE_SHIP,
                    AftersaleStatuses.REJECTED,
                    AftersaleStatuses.CANCELED)),
            Map.entry(AftersaleStatuses.FINISHED, Set.of()),
            Map.entry(AftersaleStatuses.CANCELED, Set.of())
    );

    public boolean canTransit(int from, int to) {
        return EDGES.getOrDefault(from, Set.of()).contains(to);
    }

    public void assertTransition(int from, int to) {
        if (!canTransit(from, to)) {
            throw new BizException(ErrorCode.AFTERSALE_STATUS_ERROR,
                    "售后单状态不允许从 " + from + " 流转到 " + to);
        }
    }
}
