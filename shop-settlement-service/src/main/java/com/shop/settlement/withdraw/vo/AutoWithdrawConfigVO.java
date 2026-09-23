package com.shop.settlement.withdraw.vo;

import com.shop.settlement.support.AccountMask;
import com.shop.settlement.support.DataCipher;
import com.shop.settlement.withdraw.entity.SettWithdrawAutoConfig;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 自动提现配置对外视图（M-1）：收款账号/姓名脱敏；内部自动打款链路走服务层解密后的完整值。
 */
@Data
public class AutoWithdrawConfigVO {

    private Long id;
    private Long merchantId;
    private Integer enabled;
    private Integer frequency;
    private Integer weekday;
    private Integer channel;
    /** 脱敏收款账号 */
    private String channelAccount;
    /** 脱敏收款人姓名 */
    private String accountName;
    private String bankName;
    private LocalDate lastRunDate;
    private LocalDateTime createTime;

    public static AutoWithdrawConfigVO masked(SettWithdrawAutoConfig c, DataCipher cipher) {
        AutoWithdrawConfigVO vo = new AutoWithdrawConfigVO();
        vo.id = c.getId();
        vo.merchantId = c.getMerchantId();
        vo.enabled = c.getEnabled();
        vo.frequency = c.getFrequency();
        vo.weekday = c.getWeekday();
        vo.channel = c.getChannel();
        vo.channelAccount = AccountMask.maskAccount(cipher.decrypt(c.getChannelAccount()));
        vo.accountName = AccountMask.maskName(cipher.decrypt(c.getAccountName()));
        vo.bankName = c.getBankName();
        vo.lastRunDate = c.getLastRunDate();
        vo.createTime = c.getCreateTime();
        return vo;
    }
}
