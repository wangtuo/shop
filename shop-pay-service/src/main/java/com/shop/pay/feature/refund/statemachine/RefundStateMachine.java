package com.shop.pay.feature.refund.statemachine;

import com.shop.api.pay.enums.RefundStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 退款单状态机（design 6.4）。
 *
 * <pre>
 * 10 待退款 → 20 退款中 / 40 失败
 * 20 退款中 → 30 成功 / 40 失败
 * 40 失败   → 10 待退款（重试）/ 20 退款中 / 50 已冲正
 * 30 成功   → 50 已冲正（清算冲正回滚）
 * 50 已冲正 终态
 * </pre>
 */
@Component
public class RefundStateMachine {

    private static final Map<RefundStatuses, Set<RefundStatuses>> TRANSITIONS =
            new EnumMap<>(RefundStatuses.class);

    static {
        TRANSITIONS.put(RefundStatuses.WAIT,
                EnumSet.of(RefundStatuses.PROCESSING, RefundStatuses.FAIL));
        TRANSITIONS.put(RefundStatuses.PROCESSING,
                EnumSet.of(RefundStatuses.SUCCESS, RefundStatuses.FAIL));
        TRANSITIONS.put(RefundStatuses.FAIL,
                EnumSet.of(RefundStatuses.WAIT, RefundStatuses.PROCESSING, RefundStatuses.REVERSED));
        TRANSITIONS.put(RefundStatuses.SUCCESS, EnumSet.of(RefundStatuses.REVERSED));
        TRANSITIONS.put(RefundStatuses.REVERSED, EnumSet.noneOf(RefundStatuses.class));
    }

    public void assertTransition(Integer fromCode, Integer toCode) {
        RefundStatuses from = RefundStatuses.of(fromCode);
        RefundStatuses to = RefundStatuses.of(toCode);
        if (!TRANSITIONS.getOrDefault(from, EnumSet.noneOf(RefundStatuses.class)).contains(to)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "退款单状态不允许从[" + from.getDesc() + "]流转到[" + to.getDesc() + "]");
        }
    }

    public boolean canTransition(Integer fromCode, Integer toCode) {
        try {
            assertTransition(fromCode, toCode);
            return true;
        } catch (BizException e) {
            return false;
        }
    }
}
