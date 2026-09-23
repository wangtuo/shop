package com.shop.settlement.account.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资金账户（t_sett_account）。
 * owner_id：平台/营销为 0，商户为 merchantId，用户为 userId。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_account")
public class SettAccount extends BaseEntity {

    private Long ownerId;
    /** 角色：1 平台 2 商户 3 用户 4 营销 */
    private Integer roleType;
    /** 可用余额（分；商户=可提现） */
    private Long availableFen;
    /** 冻结余额（分；提现审核/打款中） */
    private Long frozenFen;
    /** 待结算余额（分；已收货未到结算周期） */
    private Long pendingSettleFen;

    @Version
    private Integer version;
}
