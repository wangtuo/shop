package com.shop.api.order.client;

import com.shop.api.order.dto.GroupFailedCommand;
import com.shop.api.order.dto.GroupPayRenewCommand;
import com.shop.api.order.dto.GroupSucceedCommand;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderStatusDTO;
import com.shop.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 订单域跨服务 Feign 契约（CONTRACTS.md §3）。
 *
 * <p>服务端必须在 {@code shop-order-service} 的 {@code /inner/order} 同路径实现。
 */
@FeignClient(name = "shop-order-service", path = "/inner/order")
public interface OrderClient {

    /**
     * 按订单号查询订单（含收货人、发票与明细列表）。
     *
     * @param orderNo 订单号，18 位：YYMMDD + 业务类型2位 + 用户ID后4位 + 6位日内序列
     * @return 订单聚合 DTO；不存在时由服务端按错误码返回失败结果
     */
    @GetMapping
    Result<OrderDTO> getByOrderNo(@NotBlank(message = "订单号不能为空")
                                  @RequestParam("orderNo") String orderNo);

    /**
     * 订单状态批量查询（TRADE C33，一次最多 100 个订单号）。
     *
     * <p>受既有类级前缀约束，实际路径为 {@code POST /inner/order/orders/status}。
     *
     * @param orderNos 订单号列表（≤100）
     * @return orderNo → 状态快照（status/orderType/presaleFinalStage/gmtCreate）
     */
    @PostMapping("/orders/status")
    Result<Map<String, OrderStatusDTO>> listStatus(
            @Size(max = 100, message = "订单号数量不能超过 100") @RequestBody List<String> orderNos);

    /**
     * 拼团成团后续期支付截止时间（MARKETING C25，TRADE 半卡）。
     */
    @PostMapping("/group/renew")
    Result<Void> renewGroupPayDeadline(@Valid @RequestBody GroupPayRenewCommand cmd);

    /**
     * 拼团成团标记：已付款单转待发货，未付款单挂成团标记（MARKETING C25）。
     */
    @PostMapping("/group/succeed")
    Result<Void> markGroupSucceeded(@Valid @RequestBody GroupSucceedCommand cmd);

    /**
     * 拼团失败关单/退款编排（MARKETING C25）。
     */
    @PostMapping("/group/failed")
    Result<Void> markGroupFailed(@Valid @RequestBody GroupFailedCommand cmd);
}
