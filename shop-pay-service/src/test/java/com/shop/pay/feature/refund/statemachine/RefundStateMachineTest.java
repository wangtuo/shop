package com.shop.pay.feature.refund.statemachine;

import com.shop.api.pay.enums.RefundStatuses;
import com.shop.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款单状态机合法/非法边（design 6.4，10/20/30/40/50）。
 */
class RefundStateMachineTest {

    private final RefundStateMachine stateMachine = new RefundStateMachine();

    @Test
    void assertTransition_正向流转_通过() {
        stateMachine.assertTransition(RefundStatuses.WAIT.getCode(), RefundStatuses.PROCESSING.getCode());
        stateMachine.assertTransition(RefundStatuses.WAIT.getCode(), RefundStatuses.FAIL.getCode());
        stateMachine.assertTransition(RefundStatuses.PROCESSING.getCode(), RefundStatuses.SUCCESS.getCode());
        stateMachine.assertTransition(RefundStatuses.PROCESSING.getCode(), RefundStatuses.FAIL.getCode());
    }

    @Test
    void assertTransition_失败重试与冲正_通过() {
        stateMachine.assertTransition(RefundStatuses.FAIL.getCode(), RefundStatuses.WAIT.getCode());
        stateMachine.assertTransition(RefundStatuses.FAIL.getCode(), RefundStatuses.PROCESSING.getCode());
        stateMachine.assertTransition(RefundStatuses.FAIL.getCode(), RefundStatuses.REVERSED.getCode());
        stateMachine.assertTransition(RefundStatuses.SUCCESS.getCode(), RefundStatuses.REVERSED.getCode());
    }

    @Test
    void assertTransition_非法边_抛异常() {
        assertThrows(BizException.class,
                () -> stateMachine.assertTransition(RefundStatuses.WAIT.getCode(), RefundStatuses.SUCCESS.getCode()));
        assertThrows(BizException.class,
                () -> stateMachine.assertTransition(RefundStatuses.SUCCESS.getCode(), RefundStatuses.WAIT.getCode()));
        assertThrows(BizException.class,
                () -> stateMachine.assertTransition(RefundStatuses.REVERSED.getCode(), RefundStatuses.SUCCESS.getCode()));
        assertTrue(stateMachine.canTransition(
                RefundStatuses.PROCESSING.getCode(), RefundStatuses.SUCCESS.getCode()));
    }
}
