package com.shop.order.order.controller;

import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.GroupFailedCommand;
import com.shop.api.order.dto.GroupPayRenewCommand;
import com.shop.api.order.dto.GroupSucceedCommand;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderStatusDTO;
import com.shop.common.constant.BatchSizes;
import com.shop.common.result.Result;
import com.shop.framework.web.Anonymous;
import com.shop.order.order.service.GroupbuyOrderFlowService;
import com.shop.order.order.service.OrderQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订单域内部接口，路径与 {@link OrderClient} 的
 * {@code @FeignClient(path="/inner/order")} 逐字一致：GET /inner/order?orderNo=、
 * POST /inner/order/orders/status、POST /inner/order/group/renew|succeed|failed。
 * 内网服务间调用，免网关登录鉴权；售后状态变更只通过事件消费，不在此暴露写接口。
 *
 * <p>拼团三个写端点全部收敛到 {@link GroupbuyOrderFlowService} 的同一套 CAS 逻辑，
 * 与 GROUPBUY_EVENT MQ 消费（cg_order_groupbuy）双通路并存、重复到达幂等（B1）。
 */
@Anonymous
@Validated
@RestController
@RequestMapping("/inner/order")
@RequiredArgsConstructor
public class InnerOrderController {

    private final OrderQueryService orderQueryService;
    private final GroupbuyOrderFlowService groupbuyOrderFlowService;

    @GetMapping
    @com.fasterxml.jackson.annotation.JsonView(com.shop.common.jackson.JsonViews.Internal.class)
    public Result<OrderDTO> getByOrderNo(@RequestParam("orderNo") String orderNo) {
        return Result.success(orderQueryService.getByOrderNo(orderNo));
    }

    /** 订单状态批量查询（TRADE C33，≤100）。 */
    @PostMapping("/orders/status")
    public Result<Map<String, OrderStatusDTO>> listStatus(
            @Size(max = BatchSizes.IN_IDS_MAX, message = "订单号数量不能超过 100")
            @RequestBody List<String> orderNos) {
        return Result.success(orderQueryService.listStatus(orderNos));
    }

    /** 拼团成团后续期支付截止时间（MARKETING C25）。 */
    @PostMapping("/group/renew")
    public Result<Void> renewGroupPayDeadline(@Valid @RequestBody GroupPayRenewCommand command) {
        groupbuyOrderFlowService.renewGroupPayDeadline(command);
        return Result.success();
    }

    /** 拼团成团标记：未付款单续期、已付款单不动（MARKETING C25）。 */
    @PostMapping("/group/succeed")
    public Result<Void> markGroupSucceeded(@Valid @RequestBody GroupSucceedCommand command) {
        groupbuyOrderFlowService.markSucceeded(command);
        return Result.success();
    }

    /** 拼团失败关单/退款编排（MARKETING C25）。 */
    @PostMapping("/group/failed")
    public Result<Void> markGroupFailed(@Valid @RequestBody GroupFailedCommand command) {
        groupbuyOrderFlowService.markFailed(command);
        return Result.success();
    }
}
