package com.shop.marketing.coupon.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户券（design.md 4.3 生命周期）。
 * status：0未使用 1已使用 2已过期 3已作废 4已锁定（下单预核销库表中间态，CouponStatuses 之外的内部态）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_coupon")
public class UserCoupon extends BaseEntity {

    private Long userId;
    private Long couponId;
    private Integer status;
    private Integer issueWay;
    /** 发放幂等流水号 */
    private String requestNo;
    private String orderNo;
    private LocalDateTime validStartTime;
    private LocalDateTime validEndTime;
    private LocalDateTime lockTime;
    private LocalDateTime usedTime;
    private Integer version;
}
