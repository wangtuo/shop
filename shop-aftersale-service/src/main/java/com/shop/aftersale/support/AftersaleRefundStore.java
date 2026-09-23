package com.shop.aftersale.support;

import com.shop.aftersale.aftersale.entity.AftersaleRefund;
import com.shop.aftersale.aftersale.mapper.AftersaleRefundMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 售后退款单独立事务写入器（P1-11 / P2-8）。
 *
 * <p>独立 Bean 是为了让 {@code REQUIRES_NEW} 经过 Spring 代理生效（同类自调用不生效）：
 * <ul>
 *   <li>{@link #insertWaitingInNewTx}：refundNo 在调用支付域之前生成并先提交落库，
 *       外层业务事务之后无论提交还是回滚，refundNo 都不丢失，重试必须复用同一号；</li>
 *   <li>{@link #markFailInNewTx}：调支付域失败后，FAIL 状态在独立事务落库，
 *       不随外层事务回滚而消失（旧实现 setStatus(FAIL) 后 throw，FAIL 随回滚丢失）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AftersaleRefundStore {

    /** t_aftersale_refund.fail_reason VARCHAR(512)，超长截断避免写库失败。 */
    private static final int MAX_FAIL_REASON = 500;

    private final AftersaleRefundMapper refundMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void insertWaitingInNewTx(AftersaleRefund refund) {
        refundMapper.insert(refund);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailInNewTx(String refundNo, String failReason) {
        String reason = failReason == null ? "支付域退款失败" : failReason;
        if (reason.length() > MAX_FAIL_REASON) {
            reason = reason.substring(0, MAX_FAIL_REASON);
        }
        refundMapper.markFail(refundNo, reason);
    }
}
