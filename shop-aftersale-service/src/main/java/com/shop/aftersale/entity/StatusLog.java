package com.shop.aftersale.aftersale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 售后状态流转日志。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_aftersale_status_log")
public class StatusLog extends BaseEntity {

    private String aftersaleNo;
    private Integer oldStatus;
    private Integer newStatus;
    private Long operatorId;
    private Integer operatorRole;
    private String remark;
}
