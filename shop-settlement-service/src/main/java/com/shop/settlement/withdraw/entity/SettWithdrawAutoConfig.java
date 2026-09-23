package com.shop.settlement.withdraw.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 自动提现配置（t_sett_withdraw_auto_config），每商户一条。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_withdraw_auto_config")
public class SettWithdrawAutoConfig extends BaseEntity {

    private Long merchantId;
    private Integer enabled;
    /** 1 每日 2 每周 */
    private Integer frequency;
    /** 每周几（1 周一 ~ 7 周日） */
    private Integer weekday;
    private Integer channel;
    private String channelAccount;
    private String accountName;
    private String bankName;
    private LocalDate lastRunDate;

    @Version
    private Integer version;
}
