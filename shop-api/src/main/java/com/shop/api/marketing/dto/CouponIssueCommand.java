package com.shop.api.marketing.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 内部发券命令（H-1 收口）：原对外 POST /coupons/issue 移入 /inner/marketing/coupon/issue，
 * 仅允许其他域/内部运营流程经 Feign 调用，C 端不再可直接向任意 userId 发券。
 *
 * <p>requestNo 须由调用方保证唯一（消费流水/业务单号），营销域据此幂等。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponIssueCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 收券用户 ID（由服务端业务流程确定，不来自 C 端请求） */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotNull(message = "优惠券ID不能为空")
    private Long couponId;

    /** 发放方式，见 CouponIssueWays（活动/新人/补偿/积分兑换） */
    @NotNull(message = "发放方式不能为空")
    private Integer issueWay;

    /** 发放幂等流水号（调用方业务单号） */
    private String requestNo;
}
