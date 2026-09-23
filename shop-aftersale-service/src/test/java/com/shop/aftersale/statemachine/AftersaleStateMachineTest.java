package com.shop.aftersale.statemachine;

import com.shop.api.aftersale.enums.AftersaleStatuses;
import com.shop.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后状态机：全部合法边 + 典型非法边。
 */
class AftersaleStateMachineTest {

    private final AftersaleStateMachine sm = new AftersaleStateMachine();

    @Test
    void assertTransition_退款类全部合法边_通过() {
        sm.assertTransition(AftersaleStatuses.WAIT_MERCHANT_AUDIT, AftersaleStatuses.WAIT_BUYER_RETURN);
        sm.assertTransition(AftersaleStatuses.WAIT_BUYER_RETURN, AftersaleStatuses.MERCHANT_RECEIVING);
        sm.assertTransition(AftersaleStatuses.MERCHANT_RECEIVING, AftersaleStatuses.REFUNDING);
        sm.assertTransition(AftersaleStatuses.REFUNDING, AftersaleStatuses.FINISHED);
        assertTrue(sm.canTransit(AftersaleStatuses.WAIT_MERCHANT_AUDIT, AftersaleStatuses.REFUNDING));
    }

    @Test
    void assertTransition_换货支线全部合法边_通过() {
        sm.assertTransition(AftersaleStatuses.WAIT_MERCHANT_AUDIT, AftersaleStatuses.WAIT_BUYER_RETURN);
        sm.assertTransition(AftersaleStatuses.WAIT_BUYER_RETURN, AftersaleStatuses.MERCHANT_RECEIVING);
        sm.assertTransition(AftersaleStatuses.MERCHANT_RECEIVING, AftersaleStatuses.WAIT_EXCHANGE_SHIP);
        sm.assertTransition(AftersaleStatuses.WAIT_EXCHANGE_SHIP, AftersaleStatuses.EXCHANGE_SHIPPED);
        sm.assertTransition(AftersaleStatuses.EXCHANGE_SHIPPED, AftersaleStatuses.EXCHANGE_WAIT_RECEIVE);
        sm.assertTransition(AftersaleStatuses.EXCHANGE_WAIT_RECEIVE, AftersaleStatuses.FINISHED);
        // 换货 5 天超时转退款
        sm.assertTransition(AftersaleStatuses.WAIT_EXCHANGE_SHIP, AftersaleStatuses.REFUNDING);
    }

    @Test
    void assertTransition_拒绝撤销介入分支合法边_通过() {
        sm.assertTransition(AftersaleStatuses.WAIT_MERCHANT_AUDIT, AftersaleStatuses.REJECTED);
        sm.assertTransition(AftersaleStatuses.WAIT_MERCHANT_AUDIT, AftersaleStatuses.CANCELED);
        sm.assertTransition(AftersaleStatuses.WAIT_MERCHANT_AUDIT, AftersaleStatuses.PLATFORM_INTERVENING);
        sm.assertTransition(AftersaleStatuses.REJECTED, AftersaleStatuses.WAIT_MERCHANT_AUDIT);
        sm.assertTransition(AftersaleStatuses.REJECTED, AftersaleStatuses.PLATFORM_INTERVENING);
        sm.assertTransition(AftersaleStatuses.REJECTED, AftersaleStatuses.CANCELED);
        sm.assertTransition(AftersaleStatuses.PLATFORM_INTERVENING, AftersaleStatuses.REFUNDING);
        sm.assertTransition(AftersaleStatuses.PLATFORM_INTERVENING, AftersaleStatuses.WAIT_EXCHANGE_SHIP);
        sm.assertTransition(AftersaleStatuses.PLATFORM_INTERVENING, AftersaleStatuses.REJECTED);
        sm.assertTransition(AftersaleStatuses.MERCHANT_RECEIVING, AftersaleStatuses.REJECTED);
    }

    @Test
    void assertTransition_终态无出边_抛异常() {
        assertFalse(sm.canTransit(AftersaleStatuses.FINISHED, AftersaleStatuses.WAIT_MERCHANT_AUDIT));
        assertFalse(sm.canTransit(AftersaleStatuses.CANCELED, AftersaleStatuses.FINISHED));
        assertThrows(BizException.class,
                () -> sm.assertTransition(AftersaleStatuses.FINISHED, AftersaleStatuses.CANCELED));
    }

    @Test
    void assertTransition_典型非法逆向跳转_抛异常() {
        assertThrows(BizException.class,
                () -> sm.assertTransition(AftersaleStatuses.REFUNDING, AftersaleStatuses.WAIT_MERCHANT_AUDIT));
        assertThrows(BizException.class,
                () -> sm.assertTransition(AftersaleStatuses.FINISHED, AftersaleStatuses.REFUNDING));
        assertThrows(BizException.class,
                () -> sm.assertTransition(AftersaleStatuses.WAIT_BUYER_RETURN, AftersaleStatuses.REFUNDING));
        assertThrows(BizException.class,
                () -> sm.assertTransition(AftersaleStatuses.EXCHANGE_WAIT_RECEIVE,
                        AftersaleStatuses.WAIT_EXCHANGE_SHIP));
    }
}
