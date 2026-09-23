package com.shop.settlement.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 退款冲正计算结果（design 7.5）。金额单位：分。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundReverseResult {

    /** 退款占订单实付比例（万分比） */
    private int refundRatioBps;
    /** 本次应从商户处扣回的货款（应收按比例；技服费/通道费不退） */
    private long merchantPartFen;
    /** 本次回退平台佣金（按比例） */
    private long commissionReverseFen;
    /** 本次回退营销补贴（按比例，回营销账户） */
    private long subsidyReverseFen;
    /** 技术服务费不退，恒为 0（显式留字段便于对账） */
    private long techFeeReverseFen;
    /** 通道费不退，恒为 0（显式留字段便于对账） */
    private long channelFeeReverseFen;
    /** 是否全额退款（清算单 stage→40） */
    private boolean fullRefund;
}
