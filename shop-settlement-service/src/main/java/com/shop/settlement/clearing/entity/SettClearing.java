package com.shop.settlement.clearing.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 清算单（t_sett_clearing），order_no 唯一，阶段 10/20/30/40。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_clearing")
public class SettClearing extends BaseEntity {

    private String clearingNo;
    private String orderNo;
    private String payNo;
    private Long merchantId;
    private Long userId;
    /** 10 待清算 20 待结算 30 已结算 40 已冲正 */
    private Integer stage;

    private Long productAmountFen;
    private Long freightFen;
    private Long merchantBearDiscountFen;
    private Long platformBearDiscountFen;
    /** 用户实付（分） */
    private Long payAmountFen;
    private Long merchantReceivableFen;
    private Long platformCommissionFen;
    private Long techFeeFen;
    private Long channelFeeFen;
    private Long marketingSubsidyFen;
    /** 运费险保费（分，平台保险收入，退款不退；V5 DDL t_sett_clearing.insurance_premium_fen） */
    private Long insurancePremiumFen;
    private Integer commissionRateBps;

    /** 累计冲正商户货款 */
    private Long reversedMerchantFen;
    /** 累计冲正平台佣金 */
    private Long reversedCommissionFen;
    /** 累计冲正营销补贴 */
    private Long reversedSubsidyFen;
    /** 累计退款金额 */
    private Long refundedFen;

    private Long merchantStatementId;
    private LocalDateTime confirmedTime;
    private LocalDate dueDate;
    private LocalDateTime settleTime;

    @Version
    private Integer version;
}
