package com.shop.user.account.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 账户流水（t_user_account_flow）：余额/赠金借/贷 + 积分全部变动。
 * 幂等键 (biz_no, change_type) 唯一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_account_flow")
public class UserAccountFlow extends BaseEntity {

    private Long userId;

    /** 1 余额 2 赠金 3 积分 */
    private Integer accountType;

    /** 资金账户：1 贷 2 借；积分：1 获取 2 消耗 3 冻结 4 释放 5 退回 6 过期 */
    private Integer changeType;

    /** 变动数量（分或积分个） */
    private Long amount;

    /** 变动后可用余额/可用积分 */
    private Long balanceAfter;

    /** 业务单号 */
    private String bizNo;

    /** 场景（PointsScene / GrowthScene） */
    private Integer scene;

    /** 备注 */
    private String remark;

    /** 0 处理中 1 成功 */
    private Integer status;
}
