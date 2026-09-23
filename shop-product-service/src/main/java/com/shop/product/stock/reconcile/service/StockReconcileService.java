package com.shop.product.stock.reconcile.service;

/**
 * 普通库存对账服务（GAP_PLAN_TRADE P1-1）。
 *
 * <p>权威源为订单状态：扫描超过 minAge 仍悬挂的 LOCKED 流水，按订单终态补释放/补 confirm/补出账；
 * 订单查不到不自动释放，仅落 t_product_stock_reconcile_log 告警，连续两轮升级 ERROR。
 * 另含预售尾款违约定金回补扫描（type=2 status=1 → status=5）。
 */
public interface StockReconcileService {

    /**
     * 执行一轮对账。由 {@code StockReconcileJob} 在 ShedLock 单实例保护下调度；
     * 实现内逐单 try/catch，单条失败不影响整批，无外层事务。
     */
    void reconcileOnce();
}
