package com.shop.order.order.controller;

import com.shop.api.order.dto.OrderDTO;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.ratelimit.RateLimit;
import com.shop.framework.web.UserContext;
import com.shop.order.invoice.service.InvoiceService;
import com.shop.order.order.dto.AddressUpdateRequest;
import com.shop.order.order.dto.CreateOrderRequest;
import com.shop.order.order.dto.InvoiceRequest;
import com.shop.order.order.dto.OrderPageQuery;
import com.shop.order.order.service.OrderCreateService;
import com.shop.order.order.service.OrderOperateService;
import com.shop.order.order.service.OrderQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家订单 HTTP（design 5.3）：下单/查询/取消/确认/地址/催发/删除/再来一单/发票。
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCreateService orderCreateService;
    private final OrderQueryService orderQueryService;
    private final OrderOperateService orderOperateService;
    private final InvoiceService invoiceService;

    /** 提交订单（clientToken 幂等防重复提交），返回订单号。M-2：同用户 5 次/秒防刷单 */
    @PostMapping
    @RateLimit(prefix = "order:create", permits = 5, windowSeconds = 1,
            message = "下单过于频繁，请稍后再试")
    public Result<String> create(@Valid @RequestBody CreateOrderRequest request) {
        return Result.success(orderCreateService.create(request, UserContext.getUserId()));
    }

    /** 我的订单分页（状态/类型/时间） */
    @GetMapping
    public Result<PageResult<OrderDTO>> page(@ModelAttribute OrderPageQuery query) {
        return Result.success(orderQueryService.pageUser(UserContext.getUserId(), query));
    }

    /** 订单详情（买家或该单商户可查） */
    @GetMapping("/{orderNo}")
    public Result<OrderDTO> detail(@PathVariable String orderNo) {
        return Result.success(orderQueryService.detail(orderNo,
                UserContext.getUserIdOrNull(), UserContext.getMerchantIdOrNull()));
    }

    /** 取消待付款订单（已支付需走售后） */
    @PostMapping("/{orderNo}/cancel")
    public Result<Void> cancel(@PathVariable String orderNo) {
        orderOperateService.cancel(orderNo, UserContext.getUserId());
        return Result.success();
    }

    /** 确认收货（30→40） */
    @PostMapping("/{orderNo}/confirm")
    public Result<Void> confirm(@PathVariable String orderNo) {
        orderOperateService.confirm(orderNo, UserContext.getUserId());
        return Result.success();
    }

    /** 待发货修改收货地址 */
    @PutMapping("/{orderNo}/address")
    public Result<Void> updateAddress(@PathVariable String orderNo,
                                      @Valid @RequestBody AddressUpdateRequest request) {
        orderOperateService.updateAddress(orderNo, UserContext.getUserId(), request);
        return Result.success();
    }

    /** 提醒发货（仅标记） */
    @PostMapping("/{orderNo}/remind")
    public Result<Void> remind(@PathVariable String orderNo) {
        orderOperateService.remindShip(orderNo, UserContext.getUserId());
        return Result.success();
    }

    /** 删除订单（仅已取消 50 / 已关闭 70） */
    @DeleteMapping("/{orderNo}")
    public Result<Void> delete(@PathVariable String orderNo) {
        orderOperateService.delete(orderNo, UserContext.getUserId());
        return Result.success();
    }

    /** 再来一单：明细回填购物车 */
    @PostMapping("/{orderNo}/rebuy")
    public Result<Void> rebuy(@PathVariable String orderNo) {
        orderOperateService.rebuy(orderNo, UserContext.getUserId());
        return Result.success();
    }

    /** 查询发票（随订单聚合返回） */
    @GetMapping("/{orderNo}/invoice")
    public Result<OrderDTO> invoice(@PathVariable String orderNo) {
        return Result.success(invoiceService.view(orderNo, UserContext.getUserId()));
    }

    /** 提交/修改发票信息 */
    @PutMapping("/{orderNo}/invoice")
    public Result<OrderDTO> saveInvoice(@PathVariable String orderNo,
                                        @Valid @RequestBody InvoiceRequest request) {
        return Result.success(invoiceService.saveInvoice(orderNo, UserContext.getUserId(), request));
    }
}
