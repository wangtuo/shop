package com.shop.user.account.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户账户（t_user_account）：每用户 3 行（1 余额 / 2 赠金 / 3 积分）。
 * 余额/赠金 balance 单位为分；积分账户 balance 为可用积分个，frozen 为冻结积分。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_account")
public class UserAccount extends BaseEntity {

    private Long userId;

    /** 1 余额 2 赠金 3 积分 */
    private Integer accountType;

    /** 可用余额/可用积分 */
    private Long balance;

    /** 冻结额（积分下单预抵） */
    private Long frozen;

    @Version
    private Integer version;
}
