package com.shop.order.statemachine;

import com.shop.api.order.enums.OrderStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单状态机单测：10→20→30→40 主链、10→50、售后 60/61/62 进出、终态与非法跳转。
 */
class OrderStateMachineTest {

    private final OrderStateMachine stateMachine = new OrderStateMachine();

    @Test
    void legal_forwardChain_payShipConfirmComplete() {
        assertThatCode(() -> {
            stateMachine.assertTransition(OrderStatuses.WAIT_PAY, OrderStatuses.WAIT_SHIP);
            stateMachine.assertTransition(OrderStatuses.WAIT_SHIP, OrderStatuses.WAIT_RECEIVE);
            stateMachine.assertTransition(OrderStatuses.WAIT_RECEIVE, OrderStatuses.COMPLETED);
        }).doesNotThrowAnyException();
    }

    @Test
    void legal_waitPayToCancelled() {
        assertThat(stateMachine.canTransit(10, 50)).isTrue();
        assertThatCode(() -> stateMachine.assertTransition(10, 50)).doesNotThrowAnyException();
    }

    @Test
    void legal_paidStatuses_enterAllAftersaleStates() {
        for (int from : new int[]{20, 30, 40}) {
            for (int to : new int[]{60, 61, 62}) {
                assertThat(stateMachine.canTransit(from, to))
                        .as("%s -> %s 应允许售后", from, to).isTrue();
            }
        }
    }

    @Test
    void legal_aftersaleStates_resumeOrClose() {
        // 退款中/退货退款中可恢复到 20/30/40 或关闭 70
        for (int from : new int[]{60, 61}) {
            for (int to : new int[]{20, 30, 40, 70}) {
                assertThat(stateMachine.canTransit(from, to)).isTrue();
            }
        }
        // 换货中只能恢复到 30/40 或关闭（不回到待发货）
        assertThat(stateMachine.canTransit(62, 20)).isFalse();
        for (int to : new int[]{30, 40, 70}) {
            assertThat(stateMachine.canTransit(62, to)).isTrue();
        }
    }

    @Test
    void legal_completedClosesAfterAftersaleWindow() {
        // 40 直接 70：售后期结束（无售后单）
        assertThat(stateMachine.canTransit(40, 70)).isTrue();
    }

    @Test
    void illegal_edges_throwOrderStatusError() {
        Map<Integer, Integer> illegal = new LinkedHashMap<>();
        illegal.put(10, 30);   // 跳过支付
        illegal.put(10, 40);
        illegal.put(10, 60);   // 未支付不能售后
        illegal.put(10, 70);
        illegal.put(20, 10);   // 不能回退
        illegal.put(20, 50);   // 已支付不能直接取消
        illegal.put(30, 20);
        illegal.put(30, 50);
        illegal.put(40, 20);
        illegal.put(40, 30);
        illegal.put(50, 20);   // 已取消终态
        illegal.put(70, 40);   // 已关闭终态
        illegal.put(60, 10);
        illegal.put(99, 20);   // 未知状态

        for (Map.Entry<Integer, Integer> e : illegal.entrySet()) {
            int from = e.getKey();
            int to = e.getValue();
            assertThat(stateMachine.canTransit(from, to))
                    .as("%s -> %s 应非法", from, to).isFalse();
            assertThatThrownBy(() -> stateMachine.assertTransition(from, to))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode());
        }
    }

    @Test
    void assertCancelable_onlyWaitPayAllowed() {
        assertThatCode(() -> stateMachine.assertCancelable(10)).doesNotThrowAnyException();
        for (int status : new int[]{20, 30, 40, 50, 60, 61, 62, 70}) {
            assertThatThrownBy(() -> stateMachine.assertCancelable(status))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode());
        }
    }
}
