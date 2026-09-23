package com.shop.marketing.coupon.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** C 端领券请求（卡 API-M 从 CouponCenterController 内联类外提并补校验）。 */
@Data
public class ClaimRequest {

    @NotNull(message = "couponId 不能为空")
    private Long couponId;

    /** 客户端请求号：上送后按 user+coupon+requestNo 做 24h 请求级幂等；不上送仅 3s 防连点 */
    @Size(max = 64, message = "requestNo 最长64字符")
    private String requestNo;
}
