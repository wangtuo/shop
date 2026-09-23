package com.shop.settlement.clearing.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 清算冲正明细（t_sett_clearing_reverse），refund_no 唯一幂等。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_clearing_reverse")
public class SettClearingReverse extends BaseEntity {

    private String reverseNo;
    private String refundNo;
    private String orderNo;
    private String clearingNo;
    private Long merchantId;
    /** 1 全额 2 部分 */
    private Integer refundType;
    private Long refundFen;
    private Integer refundRatioBps;
    private Long reverseMerchantFen;
    private Long reverseCommissionFen;
    private Long reverseSubsidyFen;
    /** 商户部分取自待结算金额 */
    private Long fromPendingFen;
    /** 商户部分取自可提现余额（P1-10 瀑布中间档） */
    private Long fromAvailableFen;
    /** 商户部分取自保证金金额 */
    private Long fromDepositFen;
    /** 三档（待结算/可提现/保证金）合计仍不足、挂起待追讨的缺口金额（分） */
    private Long shortfallFen;
    /** 冲正状态：1 已全额扣回 2 部分扣回（缺口挂起），见 ReverseStatuses */
    private Integer status;
    /** 全额冲正：清算单转 40 */
    private Integer fullReversed;
}
