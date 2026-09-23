package com.shop.marketing.promo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 促销活动主表实体（design.md 4.1.1）。
 * type：1满减 2满折 3满赠 4第N件 5限时折扣（{@link com.shop.api.marketing.enums.ActivityTypes}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_promo")
public class Promo extends BaseEntity {

    private Long merchantId;
    private Long shopId;
    private String name;
    private Integer type;
    /** 1全部商品 2指定SKU 3指定SPU 4指定三级类目 */
    private Integer scopeType;
    /** 0下架 1上架 */
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer version;
    private String remark;

    // ---- marketing V5 审核流（D1）----
    /** 审核态：0草稿 1待审核 2通过 3驳回，存量默认 2 */
    private Integer auditStatus;
    private LocalDateTime submitTime;
    private Long auditUserId;
    private LocalDateTime auditTime;
    private String auditRemark;
}
