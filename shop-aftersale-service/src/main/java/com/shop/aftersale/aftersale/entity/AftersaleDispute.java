package com.shop.aftersale.aftersale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 平台介入单（3 天举证、5 工作日裁决）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_aftersale_dispute")
public class AftersaleDispute extends BaseEntity {

    private String aftersaleNo;
    private String orderNo;
    private Long userId;
    private Long merchantId;
    private Integer status;
    private LocalDateTime applyTime;
    private LocalDateTime evidenceDeadline;
    private LocalDateTime arbitrateDeadline;
    private Integer result;
    private Long awardFen;
    private String arbitrateRemark;
    private LocalDateTime arbitrateTime;
}
