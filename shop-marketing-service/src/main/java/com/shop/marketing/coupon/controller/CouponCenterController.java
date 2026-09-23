package com.shop.marketing.coupon.controller;

import com.shop.common.result.Result;
import com.shop.framework.ratelimit.RateLimit;
import com.shop.framework.web.Anonymous;
import com.shop.framework.web.UserContext;
import com.shop.marketing.coupon.dto.ClaimRequest;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.entity.UserCoupon;
import com.shop.marketing.coupon.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** C 端券中心 / 领取 / 我的券。 */
@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponCenterController {

    private final CouponService couponService;

    /** 领券中心：当前在领的券列表（公开浏览，与网关白名单一致）。 */
    @Anonymous
    @GetMapping("/center")
    public Result<List<Coupon>> center() {
        return Result.success(couponService.claimCenter());
    }

    /** 主动领取（每人每券按模板限领；可上送 requestNo 做请求级幂等，否则仅 3s 防连点，DB UK 兜底）。
     *  M-2：同用户 30 次/分钟频控，脚本批量扫券在此被拦。 */
    @PostMapping("/claim")
    @RateLimit(prefix = "coupon:claim", permits = 30, windowSeconds = 60,
            message = "领券操作过于频繁，请稍后再试")
    public Result<Long> claim(@Valid @RequestBody ClaimRequest req) {
        return Result.success(couponService.claim(UserContext.getUserId(), req.getCouponId(), req.getRequestNo()));
    }

    /** 我的券：status 0未使用 1已使用 2已过期 3已作废，空=全部。 */
    @GetMapping("/my")
    public Result<List<UserCoupon>> my(@RequestParam(required = false) Integer status) {
        return Result.success(couponService.myCoupons(UserContext.getUserId(), status));
    }

    // H-1：系统发券入口 POST /coupons/issue 已收口为内部 Feign 接口
    // （MarketingClient#issueCoupon → /inner/marketing/coupon/issue），C 端不可直接向任意 userId 发券。
}
