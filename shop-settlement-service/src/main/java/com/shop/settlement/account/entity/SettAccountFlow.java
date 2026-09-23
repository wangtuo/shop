package com.shop.settlement.account.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 账户流水（t_sett_account_flow），(biz_no, change_type) 唯一幂等。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_account_flow")
public class SettAccountFlow extends BaseEntity {

    private String flowNo;
    private Long ownerId;
    private Integer roleType;
    private String bizNo;
    /** 变动类型，见 FlowChangeTypes */
    private Integer changeType;
    private Long availableChange;
    private Long frozenChange;
    private Long pendingChange;
    private Long availableAfter;
    private Long frozenAfter;
    private Long pendingAfter;
    private String remark;
}
