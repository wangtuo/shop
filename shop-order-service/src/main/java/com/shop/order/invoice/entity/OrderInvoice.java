package com.shop.order.invoice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 订单发票实体（t_order_invoice，design 5.5）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order_invoice")
public class OrderInvoice extends BaseEntity {

    private String orderNo;
    private Long userId;
    private Long merchantId;
    /** 0 不开 1 电子普票 2 专票 */
    private Integer invoiceType;
    /** 1 商品明细 2 商品类别 */
    private Integer contentScope;
    /** PERSONAL / COMPANY */
    private String titleType;
    private String companyName;
    private String taxNo;
    private String email;
    /** 0 待开具 1 已开具 2 已冲红 */
    private Integer status;
    private String invoiceNo;
    private String pdfUrl;
    private LocalDateTime issueTime;
    private LocalDateTime redFlushTime;
}
