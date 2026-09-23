package com.shop.pay.feature.payment.statemachine;

import com.shop.api.pay.enums.PayStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 支付单状态机（design 6.3）：所有状态跳转集中校验，非法边抛 BizException。
 *
 * <pre>
 * 10 待支付 → 20 支付中 / 30 成功 / 40 失败 / 50 已关闭
 * 20 支付中 → 30 成功 / 40 失败 / 50 已关闭
 * 30 成功   → 60 退款中 / 70 已退款
 * 60 退款中 → 70 已退款（可继续部分退款，保持 60）
 * 40/50/70 终态（70 由全额退款产生）
 * </pre>
 */
@Component
public class PaymentStateMachine {

    private static final Map<PayStatuses, Set<PayStatuses>> TRANSITIONS = new EnumMap<>(PayStatuses.class);

    static {
        TRANSITIONS.put(PayStatuses.WAIT,
                EnumSet.of(PayStatuses.PAYING, PayStatuses.SUCCESS, PayStatuses.FAIL, PayStatuses.CLOSED));
        TRANSITIONS.put(PayStatuses.PAYING,
                EnumSet.of(PayStatuses.SUCCESS, PayStatuses.FAIL, PayStatuses.CLOSED));
        TRANSITIONS.put(PayStatuses.SUCCESS,
                EnumSet.of(PayStatuses.REFUNDING, PayStatuses.REFUNDED));
        TRANSITIONS.put(PayStatuses.REFUNDING,
                EnumSet.of(PayStatuses.REFUNDING, PayStatuses.REFUNDED));
        TRANSITIONS.put(PayStatuses.FAIL, EnumSet.noneOf(PayStatuses.class));
        TRANSITIONS.put(PayStatuses.CLOSED, EnumSet.noneOf(PayStatuses.class));
        TRANSITIONS.put(PayStatuses.REFUNDED, EnumSet.noneOf(PayStatuses.class));
    }

    public void assertTransition(Integer fromCode, Integer toCode) {
        PayStatuses from = PayStatuses.of(fromCode);
        PayStatuses to = PayStatuses.of(toCode);
        if (!TRANSITIONS.getOrDefault(from, EnumSet.noneOf(PayStatuses.class)).contains(to)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "支付单状态不允许从[" + from.getDesc() + "]流转到[" + to.getDesc() + "]");
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
