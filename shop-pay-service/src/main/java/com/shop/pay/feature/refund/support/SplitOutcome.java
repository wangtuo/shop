package com.shop.pay.feature.refund.support;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个退款 split 在段二（事务外外部动作）拿到的结果，送入 {@link RefundConvergeService} 短事务收敛。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SplitOutcome {

    public enum State {
        /** 外部动作成功（渠道退款 status=20 / 余额入账成功 / 查询或回调确认成功） */
        SUCCESS,
        /** 外部明确失败（渠道 status=30 / 余额业务拒绝）→ split 20→40 */
        FAIL,
        /** 受理中或调用异常（结果不确定，严禁当失败）→ split 保持 20，等回调/查询补偿 */
        PENDING
    }

    private Long splitId;
    private State state;
    /** 成功时回写的渠道退款号（余额为 BALANCE_CREDIT_{refundNo}） */
    private String channelRefundNo;
    private String failReason;

    public static SplitOutcome success(Long splitId, String channelRefundNo) {
        return SplitOutcome.builder().splitId(splitId).state(State.SUCCESS)
                .channelRefundNo(channelRefundNo).build();
    }

    public static SplitOutcome fail(Long splitId, String failReason) {
        return SplitOutcome.builder().splitId(splitId).state(State.FAIL).failReason(failReason).build();
    }

    public static SplitOutcome pending(Long splitId) {
        return SplitOutcome.builder().splitId(splitId).state(State.PENDING).build();
    }
}
