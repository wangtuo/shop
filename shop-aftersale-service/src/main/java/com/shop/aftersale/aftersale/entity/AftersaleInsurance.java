package com.shop.aftersale.aftersale.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 运费险理赔（每单一次，封顶 25 元，退款成功后 72h 内理赔）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_aftersale_insurance")
public class AftersaleInsurance extends BaseEntity {

    private String orderNo;
    private String aftersaleNo;
    private Long userId;
    private Long premiumFen;
    private Long claimFen;
    private Integer status;
    private LocalDateTime refundTime;
    private LocalDateTime claimDeadline;
    private LocalDateTime claimTime;
}
