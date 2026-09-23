package com.shop.marketing.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 预售订单：0定金已付 1尾款已付 2已取消（尾款超时，定金不退）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_presale_order")
public class PresaleOrder extends BaseEntity {

    private Long activityId;
    private Long userId;
    private String orderNo;
    private Long depositFen;
    /** 定金膨胀抵扣金额（定金 50 抵 100 → 100） */
    private Long inflateDeductFen;
    private Long finalPayFen;
    private LocalDateTime finalStartTime;
    private LocalDateTime finalEndTime;
    private Integer status;
    private Integer version;
}
