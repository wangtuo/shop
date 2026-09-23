package com.shop.marketing.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 营销资源锁定记录（对内 lock/confirm/release 的 orderNo 幂等锚点）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_marketing_lock")
public class MarketingLock extends BaseEntity {

    private String orderNo;
    private Long userId;
    private Integer orderType;
    private Long activityId;
    /** 预核销用户券 ID，逗号分隔 */
    private String userCouponIds;
    private String snapshotJson;
    /** 0已锁定 1已确认 2已释放 */
    private Integer status;
}
