package com.shop.marketing.inner;

import com.shop.api.marketing.client.MarketingClient;
import com.shop.api.marketing.dto.CouponIssueCommand;
import com.shop.api.marketing.dto.PriceCalcCommand;
import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.api.marketing.dto.PromotionConfirmCommand;
import com.shop.api.marketing.dto.PromotionLockCommand;
import com.shop.api.marketing.dto.PromotionReleaseCommand;
import com.shop.common.result.Result;
import com.shop.framework.web.Anonymous;
import com.shop.marketing.coupon.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 营销域对内接口（路径与 {@link MarketingClient} 逐字一致：/inner/marketing）。
 *
 * <p>R4-23：类级 {@link Anonymous} 必不可少——超时取消/拼团失败等路径由 MQ 消费或
 * 调度线程经 Feign 调用本控制器（如 /release），这些线程不携带 X-User-Id，漏标即
 * 恒 401，营销资源永不释放并毒化按 client 共享的熔断器。鉴权纵深不降级：
 * InternalTokenInterceptor 仍对 /inner/** 强制 X-Internal-Token。</p>
 */
@RestController
@Anonymous
@RequestMapping("/inner/marketing")
@RequiredArgsConstructor
public class InnerMarketingController implements MarketingClient {

    private final MarketingAppService marketingAppService;
    private final CouponService couponService;

    @Override
    @PostMapping("/calculate")
    public Result<PriceCalcResult> calculate(@Valid @RequestBody PriceCalcCommand cmd) {
        return Result.success(marketingAppService.calculate(cmd));
    }

    @Override
    @PostMapping("/lock")
    public Result<Void> lockPromotion(@Valid @RequestBody PromotionLockCommand cmd) {
        marketingAppService.lock(cmd);
        return Result.success();
    }

    @Override
    @PostMapping("/confirm")
    public Result<Void> confirmPromotion(@Valid @RequestBody PromotionConfirmCommand cmd) {
        marketingAppService.confirm(cmd);
        return Result.success();
    }

    @Override
    @PostMapping("/release")
    public Result<Void> releasePromotion(@Valid @RequestBody PromotionReleaseCommand cmd) {
        marketingAppService.release(cmd);
        return Result.success();
    }

    @Override
    @PostMapping("/coupon/issue")
    public Result<Long> issueCoupon(@Valid @RequestBody CouponIssueCommand cmd) {
        int way = cmd.getIssueWay() == null ? com.shop.api.marketing.enums.CouponIssueWays.COMPENSATE.getCode()
                : cmd.getIssueWay();
        return Result.success(couponService.issue(cmd.getUserId(), cmd.getCouponId(), way, cmd.getRequestNo()));
    }
}
