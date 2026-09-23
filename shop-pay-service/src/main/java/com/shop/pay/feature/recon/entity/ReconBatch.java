package com.shop.pay.feature.recon.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * T+1 对账批次（t_pay_recon_batch）。status 10 拉取中 20 比对完成 30 差错处理完成。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pay_recon_batch")
public class ReconBatch extends BaseEntity {

    private String batchNo;
    private LocalDate reconDate;
    private String channelCode;
    private Integer channelCount;
    private Long channelAmountFen;
    private Integer localCount;
    private Long localAmountFen;
    private Integer longCount;
    private Integer shortCount;
    private Integer mismatchCount;
    private Integer status;
    private LocalDateTime finishTime;
}
