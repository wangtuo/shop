package com.shop.product.stock.service;

import com.shop.api.aftersale.event.AftersaleChangedEvent;
import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.api.order.event.OrderCreatedEvent;
import com.shop.api.order.event.OrderShippedEvent;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.dto.StockDeductCommand;
import com.shop.api.product.dto.StockLockCommand;
import com.shop.api.product.dto.StockReleaseCommand;
import com.shop.api.product.dto.StockReturnCommand;

import java.util.List;

/**
 * 库存 TCC 与库存驱动状态流转服务（design 3.3）。
 */
public interface StockService {

    /** TCC-try：可售 → 锁定（orderNo+sku+type 幂等）。 */
    void lockStock(StockLockCommand cmd);

    /** TCC-confirm：锁定 → 占用（幂等：已扣减直接成功）。 */
    void confirmDeduct(StockDeductCommand cmd);

    /** TCC-cancel：锁定 → 可售（幂等：已释放直接成功）。 */
    void releaseStock(StockReleaseCommand cmd);

    /** 售后回库：买家责任/换货回可售，质量问题入残次仓（幂等）。 */
    void returnStock(StockReturnCommand cmd);

    /** 可售判断：商品已上架（status=3）且可售库存 ≥ qty。 */
    boolean saleable(Long skuId, Integer qty);

    /** 单个 SKU 聚合查询（五价 + 库存三栏）。 */
    SkuDTO getSku(Long skuId);

    /** 批量 SKU 查询，空入参返回空集合。 */
    List<SkuDTO> listSkus(List<Long> skuIds);

    /** 商户补货：可售库存增加，可能驱动售罄商品自动上架。 */
    void replenish(Long skuId, int qty);

    /** 消费 ORDER_CREATED：按订单活动类型映射库存类型并逐 SKU 锁定。 */
    void handleOrderCreated(OrderCreatedEvent event);

    /** 消费 ORDER_PAID：按订单锁定流水逐笔确认扣减。 */
    void handleOrderPaid(PaymentSucceededEvent event);

    /** 消费 ORDER_CANCELLED：释放全部锁定库存。 */
    void handleOrderCancelled(OrderCancelledEvent event);

    /** 消费 AFTERSALE_CHANGED：完成态退货退款/换货按责任方回库。 */
    void handleAftersaleChanged(AftersaleChangedEvent event);

    /** 消费 ORDER_SHIPPED（B13）：已扣减流水 1 → 4，占用仓出账（行级守卫，重复幂等）。 */
    void handleOrderShipped(OrderShippedEvent event);

    /**
     * 对账补偿（P1-1）：按订单补发货出账，仅对 status=1 流水执行 1 → 4，
     * 其余状态跳过；整段幂等，可被对账 Job 重复调用。
     */
    void shipOutExisting(String orderNo);

    /**
     * 对账补偿（P1-1）/预售违约回补：按订单将其全部 status=1 预售定金流水
     * 回补预售池（occupied-=、presale_stock+=、status→5）；无流水安全跳过。
     */
    void returnPresaleDeposit(String orderNo);
}
