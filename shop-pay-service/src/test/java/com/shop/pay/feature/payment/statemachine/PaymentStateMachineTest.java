package com.shop.pay.feature.payment.statemachine;

import com.shop.api.pay.enums.PayStatuses;
import com.shop.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付单状态机合法/非法边全覆盖（design 6.3）。
 */
class PaymentStateMachineTest {

    private final PaymentStateMachine stateMachine = new PaymentStateMachine();

    private void assertLegal(int from, int to) {
        stateMachine.assertTransition(from, to);
        assertTrue(stateMachine.canTransition(from, to));
    }

    private void assertIllegal(int from, int to) {
        assertThrows(BizException.class, () -> stateMachine.assertTransition(from, to));
        assertFalse(stateMachine.canTransition(from, to));
    }

    @Test
    void assertTransition_待支付全部合法边_通过() {
        assertLegal(PayStatuses.WAIT.getCode(), PayStatuses.PAYING.getCode());
        assertLegal(PayStatuses.WAIT.getCode(), PayStatuses.SUCCESS.getCode());
        assertLegal(PayStatuses.WAIT.getCode(), PayStatuses.FAIL.getCode());
        assertLegal(PayStatuses.WAIT.getCode(), PayStatuses.CLOSED.getCode());
    }

    @Test
    void assertTransition_支付中合法边_通过() {
        assertLegal(PayStatuses.PAYING.getCode(), PayStatuses.SUCCESS.getCode());
        assertLegal(PayStatuses.PAYING.getCode(), PayStatuses.FAIL.getCode());
        assertLegal(PayStatuses.PAYING.getCode(), PayStatuses.CLOSED.getCode());
    }

    @Test
    void assertTransition_成功进入退款_通过() {
        assertLegal(PayStatuses.SUCCESS.getCode(), PayStatuses.REFUNDING.getCode());
        assertLegal(PayStatuses.SUCCESS.getCode(), PayStatuses.REFUNDED.getCode());
        assertLegal(PayStatuses.REFUNDING.getCode(), PayStatuses.REFUNDING.getCode());
        assertLegal(PayStatuses.REFUNDING.getCode(), PayStatuses.REFUNDED.getCode());
    }

    @Test
    void assertTransition_终态与回退非法_抛异常() {
        assertIllegal(PayStatuses.FAIL.getCode(), PayStatuses.SUCCESS.getCode());
        assertIllegal(PayStatuses.CLOSED.getCode(), PayStatuses.SUCCESS.getCode());
        assertIllegal(PayStatuses.REFUNDED.getCode(), PayStatuses.SUCCESS.getCode());
        assertIllegal(PayStatuses.SUCCESS.getCode(), PayStatuses.WAIT.getCode());
        assertIllegal(PayStatuses.WAIT.getCode(), PayStatuses.REFUNDED.getCode());
        assertIllegal(PayStatuses.PAYING.getCode(), PayStatuses.REFUNDING.getCode());
    }
}
