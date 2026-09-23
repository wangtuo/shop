package com.shop.api.order.dto;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shop.common.jackson.JsonViews;
import com.shop.common.jackson.PhoneMaskingSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单聚合 DTO（design.md 5.1.1 订单结构）。
 *
 * <p>金额一律 {@code Long}，单位「分」。{@code orderType} 见 {@code OrderTypes}，
 * {@code status} 见 {@code OrderStatuses}，{@code source} 见 {@code OrderSources}，
 * 明细售后状态见 {@code ItemAftersaleStatuses}。
 *
 * <p>订单号规则见 CONTRACTS.md §6：YYMMDD + 业务类型2位 + 用户ID后4位 + 6位日内序列，共 18 位。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单号（18 位） */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 用户昵称（下单时快照） */
    private String userNickname;

    /**
     * 用户手机号（下单时快照）。对外默认脱敏（前 3 后 4）；内部 Feign /inner/** 经 Internal 视图取明文。
     */
    @JsonView(JsonViews.Internal.class)
    @JsonSerialize(using = PhoneMaskingSerializer.class)
    private String userPhone;

    /** 订单类型：1 普通 2 秒杀 3 拼团 4 预售 5 换货 */
    private Integer orderType;

    /** 订单状态：10 待付款 20 待发货 30 待收货 40 已完成 50 已取消 60 退款中 61 退货退款中 62 换货中 70 已关闭 */
    private Integer status;

    /** 订单来源：1 APP 2 H5 3 小程序 4 PC */
    private Integer source;

    /** 商品总额（分） */
    private Long productTotalFen;

    /** 运费（分） */
    private Long freightFen;

    /** 商品优惠金额（分） */
    private Long productDiscountFen;

    /** 店铺优惠金额（分） */
    private Long shopDiscountFen;

    /** 平台优惠金额（分，含平台券） */
    private Long platformDiscountFen;

    /** 积分抵扣金额（分） */
    private Long pointsDeductFen;

    /** 优惠总额（分） */
    private Long discountTotalFen;

    /** 实付金额（分） */
    private Long payFen;

    /** 支付方式（见支付域码值） */
    private Integer payMethod;

    /** 是否购买运费险：0 否 1 是 */
    private Integer hasFreightInsurance;

    /** 运费险保费（分，未购险为 0/null） */
    private Long insurancePremiumFen;

    /** 支付流水号 */
    private String payTransactionNo;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 发货时间 */
    private LocalDateTime shipTime;

    /** 确认收货时间（评价 15 天窗口等以此为起点） */
    private LocalDateTime confirmTime;

    /** 订单完结时间（售后期满或售后终结） */
    private LocalDateTime completeTime;

    /** 买家备注 */
    private String remark;

    /** 收货信息（快照） */
    private ReceiverDTO receiver;

    /** 发票信息 */
    private InvoiceDTO invoice;

    /** 订单明细 */
    @Builder.Default
    private List<OrderItemDTO> items = new ArrayList<>();

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
