package com.shop.order.statemachine;

import com.shop.api.order.enums.OrderStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 订单状态机（design.md 5.2，CONTRACTS.md §4）。无状态 Spring 组件，非法跳转抛
 * {@link ErrorCode#ORDER_STATUS_ERROR}；DB 更新必须再带 {@code WHERE status=?} 条件。
 *
 * <pre>
 * 10 待付款 → 20 待发货 → 30 待收货 → 40 已完成
 * 10 → 50 已取消（用户/超时）
 * 20/30/40 → 60 退款中 / 61 退货退款中 / 62 换货中
 * 售后态 → 进入前状态（部分退款/换货完成继续履约）或 70 已关闭（全额退款/售后终结）
 * </pre>
 */
@Component
public class OrderStateMachine {

    private static final Map<Integer, Set<Integer>> EDGES = Map.of(
            OrderStatuses.WAIT_PAY, Set.of(OrderStatuses.WAIT_SHIP, OrderStatuses.CANCELLED),
            OrderStatuses.WAIT_SHIP, Set.of(OrderStatuses.WAIT_RECEIVE, OrderStatuses.REFUNDING,
                    OrderStatuses.RETURN_REFUNDING, OrderStatuses.EXCHANGING),
            OrderStatuses.WAIT_RECEIVE, Set.of(OrderStatuses.COMPLETED, OrderStatuses.REFUNDING,
                    OrderStatuses.RETURN_REFUNDING, OrderStatuses.EXCHANGING),
            OrderStatuses.COMPLETED, Set.of(OrderStatuses.REFUNDING, OrderStatuses.RETURN_REFUNDING,
                    OrderStatuses.EXCHANGING, OrderStatuses.CLOSED),
            OrderStatuses.REFUNDING, Set.of(20, 30, 40, OrderStatuses.CLOSED),
            OrderStatuses.RETURN_REFUNDING, Set.of(20, 30, 40, OrderStatuses.CLOSED),
            OrderStatuses.EXCHANGING, Set.of(OrderStatuses.WAIT_RECEIVE, OrderStatuses.COMPLETED,
                    OrderStatuses.CLOSED),
            // 终态
            OrderStatuses.CANCELLED, Set.of(),
            OrderStatuses.CLOSED, Set.of()
    );

    /**
     * 断言状态可以从 {@code from} 流转到 {@code to}，非法跳转抛业务异常。
     */
    public void assertTransition(int from, int to) {
        if (!canTransit(from, to)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ERROR,
                    "订单状态不允许从 " + from + " 流转到 " + to);
        }
    }

    public boolean canTransit(int from, int to) {
        return EDGES.getOrDefault(from, Set.of()).contains(to);
    }

    /** 待付款单才允许用户/超时取消。 */
    public void assertCancelable(int currentStatus) {
        if (currentStatus != OrderStatuses.WAIT_PAY) {
            throw new BizException(ErrorCode.ORDER_STATUS_ERROR,
                    "当前订单状态(" + currentStatus + ")不允许取消；已支付订单请走售后流程");
        }
    }
}
