package com.shop.pay.feature.recon.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账差错单（t_pay_recon_diff，design 6.5）。
 * diffType 1 长款(渠道有本地无) 2 短款(本地有渠道无) 3 金额不符。
 * status 10 待处理 20 处理中 30 已处理 40 人工挂账。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pay_recon_diff")
public class ReconDiff extends BaseEntity {

    private String batchNo;
    private LocalDate reconDate;
    private String channelCode;
    private Integer diffType;
    private String payNo;
    private String orderNo;
    private String channelTxnNo;
    private Long localAmountFen;
    private Long channelAmountFen;
    private Integer status;
    /** SUPPLEMENT_ORDER / CLOSE_ORDER / ADJUST_AMOUNT / MANUAL */
    private String handleAction;
    private String handleRemark;
    private Integer retryCount;
    private Integer maxRetry;
    private LocalDateTime handleTime;
}
