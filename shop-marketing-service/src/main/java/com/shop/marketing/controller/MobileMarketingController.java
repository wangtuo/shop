package com.shop.marketing.controller;

import com.shop.api.marketing.dto.PriceCalcCommand;
import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.common.result.Result;
import com.shop.marketing.inner.MarketingAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端确认订单页促销试算（只读，内部复用同一引擎）。 */
@RestController
@RequestMapping("/h5/marketing")
@RequiredArgsConstructor
public class MobileMarketingController {

    private final MarketingAppService marketingAppService;

    /** 确认订单页试算：返回六层优惠金额与 SKU 分摊明细、价格快照。 */
    @PostMapping("/calculate")
    public Result<PriceCalcResult> calculate(@Valid @RequestBody PriceCalcCommand cmd) {
        return Result.success(marketingAppService.calculate(cmd));
    }
}
