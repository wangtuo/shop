package com.shop.api.marketing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 营销资源锁定命令（下单环节，对应 ORDER_CREATED 对营销的消费语义）。
 *
 * <p>一次调用完成：用户券预核销（{@link #userCouponIds}）、秒杀库存锁定/拼团开团参团/预售定金登记
 * （由 {@link #activityId} + orderType 区分）。以 orderNo 为幂等键，重复调用直接返回成功；
 * 订单域后续补偿失败时必须调用 release 释放。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionLockCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    @NotNull(message = "userId 不能为空")
    private Long userId;

    /** 订单号（营销锁定/核销/释放的幂等键） */
    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;

    /** 订单类型：1 普通 2 秒杀 3 拼团 4 预售 5 换货 */
    @NotNull(message = "orderType 不能为空")
    private Integer orderType;

    /** 订单商品行（与落库订单明细一致，用于券门槛复核与库存锁定） */
    @NotEmpty(message = "items 不能为空")
    @Valid
    @Builder.Default
    private List<CalcItem> items = new ArrayList<>();

    /** 试算生效的用户券 ID 列表（PriceCalcResult.usedUserCouponIds 原样回传）；拼团/秒杀为空 */
    @Builder.Default
    private List<Long> userCouponIds = new ArrayList<>();

    /** 营销活动 ID：秒杀/拼团/预售活动 ID（普通订单可空） */
    private Long activityId;

    /** 团长标记：1 团长 0 团员（拼团锁定时区分团身份）；null 按非团长处理 */
    private Integer leaderFlag;

    /** 拼团团号（与 {@link #activityId} 并列，MQ 第二道路径显式区分拼团活动） */
    private String groupNo;

    /** 拼团活动 ID（与 {@link #activityId} 并存，显式区分活动类型，不再靠“取第一个非空”推断） */
    private Long groupbuyActivityId;

    /** 试算价格快照 JSON（与订单落库快照一致，锁定时复核价格一致性） */
    private String snapshotJson;
}
