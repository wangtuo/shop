package com.shop.api.marketing.client;

import com.shop.api.marketing.dto.CouponIssueCommand;
import com.shop.api.marketing.dto.PriceCalcCommand;
import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.api.marketing.dto.PromotionConfirmCommand;
import com.shop.api.marketing.dto.PromotionLockCommand;
import com.shop.api.marketing.dto.PromotionReleaseCommand;
import com.shop.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 营销域对内 Feign 契约（CONTRACTS.md §3）。
 *
 * <p>服务名 {@code shop-marketing-service}，统一前缀 {@code /inner/marketing}，由订单域在确认订单页与下单链路上同步调用：
 * <ul>
 *   <li>{@link #calculate} 确认订单页/下单前试算：按 design.md 4.2.1 叠加顺序与 4.2.3 互斥规则返回整单金额与 SKU 级分摊；</li>
 *   <li>{@link #lockPromotion} 下单（ORDER_CREATED）：预核销用户券、锁定秒杀库存/拼团/预售营销资源；</li>
 *   <li>{@link #confirmPromotion} 支付成功（ORDER_PAID）：正式核销券、秒杀库存扣减；</li>
 *   <li>{@link #releasePromotion} 订单取消/超时（ORDER_CANCELLED）：释放预核销券与营销锁定资源。</li>
 * </ul>
 *
 * <p>实现方必须在 {@code /inner/marketing} 同路径提供内部 Controller，方法签名与本接口逐字一致；
 * 金额一律 Long（分），禁止 double/float。
 */
@FeignClient(name = "shop-marketing-service", path = "/inner/marketing")
public interface MarketingClient {

    /** 确认订单页/下单前试算优惠价格（不落库、不锁资源，纯计算）。 */
    @PostMapping("/calculate")
    Result<PriceCalcResult> calculate(@Valid @RequestBody PriceCalcCommand cmd);

    /** 下单时预核销券/锁定秒杀库存（与订单落库同事务边界，失败需逆序补偿）。 */
    @PostMapping("/lock")
    Result<Void> lockPromotion(@Valid @RequestBody PromotionLockCommand cmd);

    /** 支付成功后核销券/秒杀库存扣减（幂等，以 orderNo 去重）。 */
    @PostMapping("/confirm")
    Result<Void> confirmPromotion(@Valid @RequestBody PromotionConfirmCommand cmd);

    /** 订单取消/超时未付，释放预核销券与锁定的营销资源（幂等，以 orderNo 去重）。 */
    @PostMapping("/release")
    Result<Void> releasePromotion(@Valid @RequestBody PromotionReleaseCommand cmd);

    /**
     * 内部发券（活动发放/新人礼包/系统补偿/积分兑换）。原对外 POST /coupons/issue 收口为内部接口，
     * 仅其他域/内部运营流程可调用；以 requestNo 幂等。
     */
    @PostMapping("/coupon/issue")
    Result<Long> issueCoupon(@Valid @RequestBody CouponIssueCommand cmd);
}
