package com.shop.aftersale.aftersale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 平台介入举证凭证。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_aftersale_evidence")
public class AftersaleEvidence extends BaseEntity {

    private String aftersaleNo;
    private Integer side;
    private Long userId;
    private Integer evidenceType;
    private String content;
    private String mediaUrls;
}
