package com.shop.marketing.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 砍价记录：0砍价中 1已成交 2已失效。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_bargain_record")
public class BargainRecord extends BaseEntity {

    private Long activityId;
    private Long skuId;
    private Long userId;
    private String orderNo;
    private Long originPriceFen;
    private Long floorPriceFen;
    private Long currentPriceFen;
    private Integer helpCount;
    private Integer status;
    private LocalDateTime expireTime;
    private Integer version;
}
