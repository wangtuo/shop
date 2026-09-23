package com.shop.order.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 订单主表实体（t_order_order，design 5.1.1）。
 * 金额单位均为「分」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order_order")
public class Order extends BaseEntity {

    /** 订单号（18 位） */
    private String orderNo;
    private Long userId;
    private String userNickname;
    private String userPhone;
    /** 一单一店铺 */
    private Long merchantId;
    private Long shopId;
    /** 1 普通 2 秒杀 3 拼团 4 预售 5 换货 */
    private Integer orderType;
    /** 10/20/30/40/50/60/61/62/70 */
    private Integer status;
    /** 1 APP 2 H5 3 小程序 4 PC */
    private Integer source;

    private Long productTotalFen;
    private Long freightFen;
    private Long productDiscountFen;
    private Long shopDiscountFen;
    private Long platformDiscountFen;
    private Long pointsDeductFen;
    private Long discountTotalFen;
    private Long payFen;
    /** 使用积分个数 */
    private Long usedPoints;
    private Long userCouponId;

    /** 是否购运费险 0 否 1 是（V3，FUNDS B11） */
    private Integer hasFreightInsurance;
    /** 运费险保费（分，不退；未购为 0）（V3，FUNDS B11） */
    private Long insurancePremiumFen;

    private Integer payMethod;
    private String payNo;
    private String payTransactionNo;
    private LocalDateTime payTime;

    private LocalDateTime expireTime;
    private LocalDateTime shipTime;
    private LocalDateTime confirmTime;
    private LocalDateTime aftersaleDeadline;
    private LocalDateTime completeTime;
    private LocalDateTime cancelTime;
    private Integer cancelType;

    private String logisticsNo;
    private String logisticsCompany;
    private LocalDateTime autoConfirmDeadline;

    private String receiver;
    private String receiverPhone;
    private String province;
    private String city;
    private String district;
    private String detailAddress;
    private Long addressId;

    private String remark;
    /** 营销试算价格快照 JSON */
    private String priceSnapshot;

    private Long seckillActivityId;
    private Long groupbuyActivityId;
    private String groupNo;
    private Long presaleActivityId;
    private Integer presaleFinalStage;

    /** 进入售后态前的订单状态（售后终结恢复用） */
    private Integer preAftersaleStatus;
    /** 最近一次提醒发货时间 */
    private LocalDateTime remindTime;

    @Version
    private Integer version;
}
