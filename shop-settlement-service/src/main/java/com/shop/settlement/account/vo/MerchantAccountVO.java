package com.shop.settlement.account.vo;

import lombok.Builder;
import lombok.Data;

/** 商户账户余额视图（可提现/冻结/待结算 + 保证金状态）。 */
@Data
@Builder
public class MerchantAccountVO {

    private Long merchantId;
    /** 可提现余额（分） */
    private Long availableFen;
    /** 冻结余额（分，提现审核/打款中） */
    private Long frozenFen;
    /** 待结算余额（分，未到等级结算周期） */
    private Long pendingSettleFen;
    /** 保证金余额（分） */
    private Long depositBalanceFen;
    /** 应缴保证金（分） */
    private Long depositRequiredFen;
    /** 保证金预警阈值（应缴 50%，分） */
    private Long depositThresholdFen;
    /** 是否因保证金低于 50% 被限制提现 */
    private Boolean withdrawBlocked;
}
