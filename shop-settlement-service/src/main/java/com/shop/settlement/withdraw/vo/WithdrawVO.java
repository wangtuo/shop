package com.shop.settlement.withdraw.vo;

import com.shop.settlement.support.AccountMask;
import com.shop.settlement.support.DataCipher;
import com.shop.settlement.withdraw.entity.SettWithdraw;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 提现单对外视图（M-1）：收款账号/收款人姓名脱敏，内部打款使用的完整值仅服务层解密可见。
 */
@Data
public class WithdrawVO {

    private Long id;
    private String withdrawNo;
    private Long merchantId;
    private Long amountFen;
    private Long feeFen;
    private Integer channel;
    /** 脱敏收款账号：**** **** **** 1234 */
    private String channelAccount;
    /** 脱敏收款人姓名 */
    private String accountName;
    private String bankName;
    private Integer status;
    private LocalDate applyDate;
    private Integer freeOfCharge;
    private Integer autoWithdraw;
    private String failReason;
    private LocalDateTime auditTime;
    private LocalDateTime remitTime;
    private LocalDateTime createTime;

    /** 实体 → 对外脱敏 VO（实体中为密文，先解密再脱敏）。 */
    public static WithdrawVO masked(SettWithdraw w, DataCipher cipher) {
        WithdrawVO vo = new WithdrawVO();
        vo.id = w.getId();
        vo.withdrawNo = w.getWithdrawNo();
        vo.merchantId = w.getMerchantId();
        vo.amountFen = w.getAmountFen();
        vo.feeFen = w.getFeeFen();
        vo.channel = w.getChannel();
        vo.channelAccount = AccountMask.maskAccount(cipher.decrypt(w.getChannelAccount()));
        vo.accountName = AccountMask.maskName(cipher.decrypt(w.getAccountName()));
        vo.bankName = w.getBankName();
        vo.status = w.getStatus();
        vo.applyDate = w.getApplyDate();
        vo.freeOfCharge = w.getFreeOfCharge();
        vo.autoWithdraw = w.getAutoWithdraw();
        vo.failReason = w.getFailReason();
        vo.auditTime = w.getAuditTime();
        vo.remitTime = w.getRemitTime();
        vo.createTime = w.getCreateTime();
        return vo;
    }
}
