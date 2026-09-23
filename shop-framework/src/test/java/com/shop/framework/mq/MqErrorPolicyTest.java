package com.shop.framework.mq;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P2-3：终态业务错误 ACK 丢弃，其余可恢复错误走重试。 */
class MqErrorPolicyTest {

    @Test
    void 参数错误_终态() {
        assertTrue(MqErrorPolicy.isTerminal(new BizException(ErrorCode.PARAM_INVALID)));
    }

    @Test
    void 验签失败_终态() {
        assertTrue(MqErrorPolicy.isTerminal(new BizException(ErrorCode.PAY_SIGN_ERROR)));
    }

    @Test
    void 退款金额越界_终态() {
        assertTrue(MqErrorPolicy.isTerminal(new BizException(ErrorCode.AFTERSALE_AMOUNT_EXCEED)));
    }

    @Test
    void 冲突与依赖故障_可恢复() {
        assertFalse(MqErrorPolicy.isTerminal(new BizException(ErrorCode.CONFLICT)));
        assertFalse(MqErrorPolicy.isTerminal(new BizException(ErrorCode.DEPENDENCY_FAIL)));
        assertFalse(MqErrorPolicy.isTerminal(new BizException(ErrorCode.DEPOSIT_NOT_ENOUGH)));
        assertFalse(MqErrorPolicy.isTerminal(new BizException(ErrorCode.STOCK_NOT_ENOUGH)));
    }

    @Test
    void 引用聚合不存在_可恢复_走重试与DLQ而非静默ACK() {
        // 跨服务事件竞态/复制延迟下 NOT_FOUND 可能稍后成立；ACK 丢弃会永久丢副作用
        assertFalse(MqErrorPolicy.isTerminal(new BizException(ErrorCode.NOT_FOUND)));
        assertFalse(MqErrorPolicy.isTerminal(new BizException(ErrorCode.ORDER_NOT_FOUND)));
    }

    @Test
    void 非业务异常_默认可恢复() {
        assertFalse(MqErrorPolicy.isTerminal(new RuntimeException("connect reset")));
        assertFalse(MqErrorPolicy.isTerminal(new IllegalStateException()));
    }
}
