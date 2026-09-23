package com.shop.api.pay.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 退款单对外契约（design 6.4 退款规则）。
 *
 * <p>金额一律为 Long 分（CONTRACTS.md §2.2）；</p>
 * <ul>
 *     <li>payMethod 取值见 {@code com.shop.api.pay.enums.PayMethods}；</li>
 *     <li>refundType 取值见 {@code com.shop.api.pay.enums.RefundTypes}（1 全额 2 部分）；</li>
 *     <li>status 取值见 {@code com.shop.api.pay.enums.RefundStatuses}
 *     （10 待退款 20 退款中 30 成功 40 失败 50 已冲正）。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 退款单号（{@code R}+17 位） */
    private String refundNo;

    /** 原支付单号 */
    private String payNo;

    /** 原业务订单号 */
    private String orderNo;

    /** 关联售后单号（价保 / 清算冲正场景可为空） */
    private String aftersaleNo;

    /** 退款归属用户 ID（收款人） */
    private Long userId;

    /** 退款金额，单位：分 */
    private Long amountFen;

    /** 退款渠道/原支付方式，取值见 PayMethods */
    private Integer payMethod;

    /** 退款类型，取值见 RefundTypes（1 全额退款 2 部分退款） */
    private Integer refundType;

    /** 退款单状态，取值见 RefundStatuses（10 待退款 … 50 已冲正） */
    private Integer status;

    /** 渠道退款流水号（渠道退款成功后返回，对账主键之一） */
    private String channelRefundNo;

    /** 退款单创建时间 */
    private LocalDateTime createTime;

    /** 退款完成时间（渠道异步通知退款成功的时间） */
    private LocalDateTime finishTime;

    /** 渠道退款查询状态（渠道异步受理查询结果码，供运营侧观察；未查询为空） */
    private Integer queryStatus;
}
