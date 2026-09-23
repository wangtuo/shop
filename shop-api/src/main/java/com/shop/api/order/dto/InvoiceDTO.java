package com.shop.api.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 发票信息 DTO（design.md 5.5 发票）。
 *
 * <p>发票在订单完成后开具；电子发票发送至 {@link #email}，退款时发票自动冲红。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 发票类型：0 不开发票 1 电子普通发票 2 增值税专用发票 */
    private Integer invoiceType;

    /** 发票内容范围：1 商品明细 2 商品类别 */
    private Integer contentScope;

    /** 抬头类型：PERSONAL 个人 / COMPANY 企业（企业需填公司名称与税号） */
    private String titleType;

    /** 公司名称（企业抬头） */
    private String companyName;

    /** 纳税人识别号（企业抬头） */
    private String taxNo;

    /** 电子发票接收邮箱 */
    private String email;

    /** 开票状态：0 待开具 1 已开具 2 已冲红（不开发票同样为登记态 0，扫描开具时跳过） */
    private Integer status;

    /** 发票号（订单完成后由定时任务开具回填，开具前为空） */
    private String invoiceNo;

    /** 电子发票 PDF 地址（开具后回填） */
    private String pdfUrl;

    /** 开具时间（开具后回填） */
    private LocalDateTime issueTime;
}
