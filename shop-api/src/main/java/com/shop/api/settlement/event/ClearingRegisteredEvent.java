package com.shop.api.settlement.event;

import com.shop.api.settlement.dto.ClearingBreakdown;
import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 清算登记事件（支付成功后登记待清算）。
 *
 * <p>事件链路（CONTRACTS.md §5）：settlement 消费 ORDER_PAID（PaymentSucceededEvent），
 * 按 design 7.2.2 分账公式完成分账快照后发送本事件（Topic：{@code CLEARING_REGISTER}），
 * 阶段为 {@code WAIT_CLEAR(10)}，资金停留在平台收款账户。
 *
 * <p>{@code breakdown} 为完整清算快照；{@code orderNo}、{@code merchantId} 冗余平铺，
 * 便于消费方过滤与日志排查，无需解析快照。事件体字段自包含，禁止回查生产方库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class ClearingRegisteredEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 清算分账结果快照（含清算单号、各项费用与阶段） */
    private ClearingBreakdown breakdown;

    /** 业务订单号（冗余，便于消费） */
    private String orderNo;

    /** 商户 ID（冗余，便于消费） */
    private Long merchantId;
}
