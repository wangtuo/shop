package com.shop.marketing.coupon.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 优惠券模板（design.md 4.1.2，CouponTypes 1~6）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_coupon")
public class Coupon extends BaseEntity {

    private Long merchantId;
    private Long shopId;
    private String name;
    /** 1满减 2折扣 3无门槛 4免邮 5品类 6店铺 */
    private Integer type;
    /** 1全场 2指定SKU 3指定SPU 4指定三级类目 5指定店铺 */
    private Integer scopeType;
    private Long faceValueFen;
    private Long thresholdFen;
    private Integer discountBp;
    private Long maxDiscountFen;
    private Integer totalCount;
    private Integer receivedCount;
    private Integer perUserLimit;
    /** 1主动领取 2活动发放 3新人礼包 4系统补偿 5积分兑换 */
    private Integer issueWay;
    /** 1固定时间段 2领取后N天 */
    private Integer validType;
    private Integer validDays;
    private LocalDateTime validStartTime;
    private LocalDateTime validEndTime;
    private LocalDateTime receiveStartTime;
    private LocalDateTime receiveEndTime;
    /** 0下架 1上架 2作废 */
    private Integer status;
    private Integer version;

    // ---- marketing V5（D1）----
    /** 审核态：0草稿 1待审核 2通过 3驳回，存量默认 2 */
    private Integer auditStatus;
    private LocalDateTime submitTime;
    private Long auditUserId;
    private LocalDateTime auditTime;
    private String auditRemark;
    /** 新人礼包标记：0否 1是（注册事件自动发放，需 issue_way=3） */
    private Integer newUserGift;
}
