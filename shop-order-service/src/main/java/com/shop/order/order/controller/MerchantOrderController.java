package com.shop.order.order.controller;

import com.shop.api.order.dto.OrderDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.web.UserContext;
import com.shop.order.order.dto.OrderPageQuery;
import com.shop.order.order.dto.ShipRequest;
import com.shop.order.order.service.OrderOperateService;
import com.shop.order.order.service.OrderQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户订单 HTTP：仅商户身份可访问，Service 层强制 merchantId 归属过滤。
 */
@RestController
@RequestMapping("/merchant/orders")
@RequiredArgsConstructor
public class MerchantOrderController {

    private final OrderQueryService orderQueryService;
    private final OrderOperateService orderOperateService;

    /** 本店订单分页（状态/类型/时间） */
    @GetMapping
    public Result<PageResult<OrderDTO>> page(@ModelAttribute OrderPageQuery query) {
        return Result.success(orderQueryService.pageMerchant(currentMerchantId(), query));
    }

    /** 发货（20→30，写物流单号并发自动收货延时） */
    @PostMapping("/{orderNo}/ship")
    public Result<Void> ship(@PathVariable String orderNo,
                             @Valid @RequestBody ShipRequest request) {
        orderOperateService.ship(orderNo, currentMerchantId(), request);
        return Result.success();
    }

    private Long currentMerchantId() {
        Long merchantId = UserContext.getMerchantIdOrNull();
        if (merchantId == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅商户可操作");
        }
        return merchantId;
    }
}
