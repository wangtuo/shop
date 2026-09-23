package com.shop.product.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.api.aftersale.enums.AftersaleStatuses;
import com.shop.api.aftersale.enums.AftersaleTypes;
import com.shop.api.aftersale.enums.ResponsibilitySide;
import com.shop.api.aftersale.event.AftersaleChangedEvent;
import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderItemDTO;
import com.shop.api.order.enums.OrderTypes;
import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.api.order.event.OrderCreatedEvent;
import com.shop.api.order.event.OrderItemMessage;
import com.shop.api.order.event.OrderShippedEvent;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.dto.StockDeductCommand;
import com.shop.api.product.dto.StockItemCommand;
import com.shop.api.product.dto.StockLockCommand;
import com.shop.api.product.dto.StockReleaseCommand;
import com.shop.api.product.dto.StockReturnCommand;
import com.shop.api.product.enums.StockLockStatuses;
import com.shop.api.product.enums.StockReturnReasons;
import com.shop.api.product.enums.StockTypes;
import com.shop.api.product.event.StockWarningEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.feign.FeignResults;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.entity.ProductSpu;
import com.shop.product.goods.mapper.ProductSkuMapper;
import com.shop.product.goods.mapper.ProductSpuMapper;
import com.shop.product.goods.support.GoodsDtoAssembler;
import com.shop.product.goods.support.SpuDetailCache;
import com.shop.product.mq.MqConsumeRecordMapper;
import com.shop.product.stock.entity.ProductStockLog;
import com.shop.product.stock.entity.StockWarning;
import com.shop.product.stock.mapper.ProductStockLogMapper;
import com.shop.product.stock.mapper.StockWarningMapper;
import com.shop.product.stock.service.StockService;
import com.shop.product.support.AuthUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 库存服务实现：
 * <ol>
 *   <li>全部扣减走条件更新（available/locked/occupied >= qty），热点 SKU 叠加
 *       Redisson 锁 {@code lock:stock:{skuId}}；</li>
 *   <li>TCC 状态以 t_product_stock_log 唯一约束（order_no+sku+type）幂等；</li>
 *   <li>每次变动后做库存预警（≤阈值发 STOCK_WARNING）与 SPU 状态驱动
 *       （可售 0 在售→售罄下架；补货可售>0 售罄→自动上架）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    private static final Logger log = LoggerFactory.getLogger(StockServiceImpl.class);

    /** 热点 SKU 分布式锁 key 前缀 */
    private static final String LOCK_PREFIX = "lock:stock:";
    /** 预警阈值缺省值（design 3.3.3） */
    private static final long DEFAULT_WARN_THRESHOLD = 10L;

    /** O6 库存预警族计数器（命名冻结 GAP_PLAN_OBSERVABILITY O6）；标签仅 level，严禁 skuId/spuId/merchantId。 */
    private static final String STOCK_ALERT_TOTAL = "shop_stock_alert_total";
    /** 可售跌破阈值（含归零）的库存预警。 */
    private static final String ALERT_LEVEL_WARNING = "WARNING";
    /** 在售 SPU 全部 SKU 可售归零，自动售罄下架。 */
    private static final String ALERT_LEVEL_SOLD_OUT = "SOLD_OUT";
    /** 补货/回库后售罄 SPU 可售恢复，自动重新上架。 */
    private static final String ALERT_LEVEL_RESTOCK = "RESTOCK";

    private final ProductSkuMapper skuMapper;
    private final ProductSpuMapper spuMapper;
    private final ProductStockLogMapper stockLogMapper;
    private final StockWarningMapper warningMapper;
    private final MqConsumeRecordMapper consumeRecordMapper;
    private final IdGenerator idGenerator;
    private final DistributedLockTemplate lockTemplate;
    private final OutboxPublisher outboxPublisher;
    private final SpuDetailCache spuDetailCache;
    private final OrderClient orderClient;

    /**
     * 非构造注入：无 MeterRegistry 的纯单测环境（Mockito 手工 new）保持 null，
     * 计数调用全部空转安全（口径对齐 framework BizMetrics）。
     */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    // ------------------------------------------------------------------
    // TCC
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lockStock(StockLockCommand cmd) {
        // B5：预售单下单不锁库存（同步 Feign 道）。定金支付后才从 presale_stock 扣减入占用，
        // 此处直接幂等成功：不写流水、不动任何库存仓。
        if (Integer.valueOf(OrderTypes.PRESALE).equals(cmd.getOrderType())) {
            log.info("预售单下单不锁库存 orderNo={}", cmd.getOrderNo());
            return;
        }
        for (StockItemCommand item : cmd.getItems()) {
            int type = resolveType(item.getStockType());
            // 兼容未传 orderType 但明细已按预售库存类型上送的调用方
            if (type == StockTypes.PRESALE.getCode()) {
                log.info("预售库存类型下单不锁库存 orderNo={} skuId={}", cmd.getOrderNo(), item.getSkuId());
                continue;
            }
            ProductStockLog exist = stockLogMapper.selectByUk(cmd.getOrderNo(), item.getSkuId(), type);
            if (exist != null) {
                // 锁定已执行过（任何终态）：幂等成功，不重复扣库存
                continue;
            }
            doLock(cmd.getOrderNo(), item, type);
        }
    }

    private void doLock(String orderNo, StockItemCommand item, int type) {
        Long skuId = item.getSkuId();
        int qty = item.getQty();
        lockTemplate.execute(LOCK_PREFIX + skuId, () -> {
            ProductStockLog again = stockLogMapper.selectByUk(orderNo, skuId, type);
            if (again != null) {
                return null;
            }
            ProductSku sku = requireSku(skuId);
            int rows = skuMapper.lockStock(skuId, qty);
            if (rows == 0) {
                throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                        "SKU[" + skuId + "] 可售库存不足，需要 " + qty + " 件");
            }
            ProductStockLog stockLog = new ProductStockLog();
            stockLog.setOrderNo(orderNo);
            stockLog.setSkuId(skuId);
            stockLog.setSpuId(sku.getSpuId());
            stockLog.setMerchantId(sku.getMerchantId());
            stockLog.setType(type);
            stockLog.setQty(qty);
            stockLog.setStatus(StockLockStatuses.LOCKED.getCode());
            stockLogMapper.insert(stockLog);
            return null;
        });
        afterStockChanged(skuId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmDeduct(StockDeductCommand cmd) {
        for (StockItemCommand item : cmd.getItems()) {
            int type = resolveType(item.getStockType());
            ProductStockLog stockLog = stockLogMapper.selectByUk(cmd.getOrderNo(), item.getSkuId(), type);
            if (stockLog == null) {
                throw new BizException(ErrorCode.CONFLICT, "库存锁定流水不存在，不能确认扣减：" + cmd.getOrderNo());
            }
            confirmExisting(stockLog);
        }
    }

    private void confirmExisting(ProductStockLog stockLog) {
        int status = stockLog.getStatus();
        if (status == StockLockStatuses.DEDUCTED.getCode()) {
            return;
        }
        if (status != StockLockStatuses.LOCKED.getCode()) {
            throw new BizException(ErrorCode.CONFLICT,
                    "库存流水状态为 " + status + "，不能确认扣减（仅锁定中可扣减）");
        }
        int rows = skuMapper.confirmDeduct(stockLog.getSkuId(), stockLog.getQty());
        if (rows == 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "锁定库存数据异常，确认扣减失败");
        }
        if (stockLogMapper.updateStatusIf(stockLog.getId(),
                StockLockStatuses.LOCKED.getCode(), StockLockStatuses.DEDUCTED.getCode()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "库存流水并发冲突，确认扣减失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseStock(StockReleaseCommand cmd) {
        for (StockItemCommand item : cmd.getItems()) {
            int type = resolveType(item.getStockType());
            ProductStockLog stockLog = stockLogMapper.selectByUk(cmd.getOrderNo(), item.getSkuId(), type);
            if (stockLog == null) {
                throw new BizException(ErrorCode.CONFLICT, "库存锁定流水不存在，不能释放：" + cmd.getOrderNo());
            }
            releaseExisting(stockLog);
        }
    }

    private void releaseExisting(ProductStockLog stockLog) {
        int status = stockLog.getStatus();
        if (status == StockLockStatuses.RELEASED.getCode()) {
            return;
        }
        if (status != StockLockStatuses.LOCKED.getCode()) {
            throw new BizException(ErrorCode.CONFLICT,
                    "库存流水状态为 " + status + "，不能释放（已扣减请走售后回库）");
        }
        int rows = skuMapper.releaseStock(stockLog.getSkuId(), stockLog.getQty());
        if (rows == 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "锁定库存数据异常，释放失败");
        }
        if (stockLogMapper.updateStatusIf(stockLog.getId(),
                StockLockStatuses.LOCKED.getCode(), StockLockStatuses.RELEASED.getCode()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "库存流水并发冲突，释放失败");
        }
        afterStockChanged(stockLog.getSkuId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void returnStock(StockReturnCommand cmd) {
        int reason = cmd.getReason() == null
                ? StockReturnReasons.BUYER.getCode() : cmd.getReason();
        StockReturnReasons.fromCode(reason);
        for (StockItemCommand item : cmd.getItems()) {
            ProductStockLog stockLog = stockLogMapper.selectDeductedByOrderAndSku(cmd.getOrderNo(), item.getSkuId());
            if (stockLog == null) {
                throw new BizException(ErrorCode.CONFLICT, "无已扣减库存可回库：" + cmd.getOrderNo());
            }
            returnExisting(stockLog, reason);
        }
    }

    private void returnExisting(ProductStockLog stockLog, int reason) {
        if (stockLog.getStatus() == StockLockStatuses.RETURNED.getCode()) {
            return;
        }
        int status = stockLog.getStatus();
        int rows;
        if (status == StockLockStatuses.DEDUCTED.getCode()) {
            // 发货前退货：货物仍在占用仓 → 占用仓逆运算
            if (reason == StockReturnReasons.QUALITY.getCode()) {
                // 质量问题：占用 → 残次仓，不回可售
                rows = skuMapper.returnToDefect(stockLog.getSkuId(), stockLog.getQty());
            } else {
                // 买家责任 / 换货：占用 → 可售
                rows = skuMapper.returnToAvailable(stockLog.getSkuId(), stockLog.getQty());
            }
        } else if (status == StockLockStatuses.SHIPPED_ACCOUNTED.getCode()) {
            // B13 已出账退货（发货后退货退款）：货已物理出仓、占用早已出账，
            // 只按责任方回可售/残次，不再减 occupied。
            if (reason == StockReturnReasons.QUALITY.getCode()) {
                rows = skuMapper.returnShippedToDefect(stockLog.getSkuId(), stockLog.getQty());
            } else {
                rows = skuMapper.returnShippedToAvailable(stockLog.getSkuId(), stockLog.getQty());
            }
        } else {
            throw new BizException(ErrorCode.CONFLICT,
                    "库存流水状态为 " + status + "，不能回库（仅已扣减/已出账可回库）");
        }
        if (rows == 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "库存数据异常，回库失败");
        }
        if (stockLogMapper.markReturned(stockLog.getId(), reason) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "库存流水并发冲突，回库失败");
        }
        if (reason != StockReturnReasons.QUALITY.getCode()) {
            afterStockChanged(stockLog.getSkuId());
        }
    }

    @Override
    public boolean saleable(Long skuId, Integer qty) {
        if (skuId == null || qty == null || qty <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "skuId 与正整数 qty 必填");
        }
        ProductSku sku = skuMapper.selectById(skuId);
        return sku != null && sku.getStatus() != null && sku.getStatus() == 3
                && sku.getAvailableStock() != null && sku.getAvailableStock() >= qty;
    }

    @Override
    public SkuDTO getSku(Long skuId) {
        return GoodsDtoAssembler.toSkuDTO(requireSku(skuId));
    }

    @Override
    public List<SkuDTO> listSkus(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<ProductSku> skus = skuMapper.selectBatchIds(skuIds);
        return skus.stream().map(GoodsDtoAssembler::toSkuDTO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replenish(Long skuId, int qty) {
        if (qty <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "补货数量必须为正整数");
        }
        ProductSku sku = requireSku(skuId);
        AuthUtils.checkOwner(sku.getMerchantId());
        if (skuMapper.replenish(skuId, qty) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "SKU 不存在");
        }
        afterStockChanged(skuId);
    }

    // ------------------------------------------------------------------
    // 变动后处理：预警 + 售罄/补货自动状态流转
    // ------------------------------------------------------------------

    private void afterStockChanged(Long skuId) {
        ProductSku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            return;
        }
        long threshold = sku.getWarnThreshold() == null ? DEFAULT_WARN_THRESHOLD : sku.getWarnThreshold();
        long available = sku.getAvailableStock() == null ? 0L : sku.getAvailableStock();
        if (available <= threshold) {
            // R4-25：低库存区间边沿化——CAS 0→1 赢得本轮预警权才发，持续低库存下的后续变动静默；
            // 否则每次锁库/释放/回库/补货都以同 ('shop_stock_warning','',skuId) 第二次登记 outbox，
            // 撞 UK 回滚所在业务事务（下单失败/取消永不回库/补货被拒/对账每轮失败）。
            if (skuMapper.casLowStockAlertOn(skuId) == 1) {
                fireStockWarning(sku, available, threshold);
            }
        } else {
            // 可售恢复到阈值以上：重新武装，下一次跌破再发一轮
            skuMapper.casLowStockAlertOff(skuId);
        }
        driveSpuStatus(sku, available);
    }

    private void fireStockWarning(ProductSku sku, long available, long threshold) {
        StockWarning warning = new StockWarning();
        warning.setSkuId(sku.getId());
        warning.setSpuId(sku.getSpuId());
        warning.setMerchantId(sku.getMerchantId());
        warning.setAvailable(available);
        warning.setThreshold(threshold);
        warningMapper.insert(warning);

        StockWarningEvent event = StockWarningEvent.builder()
                .skuId(sku.getId())
                .spuId(sku.getSpuId())
                .merchantId(sku.getMerchantId())
                .available(available)
                .threshold(threshold)
                .build();
        event.setBizNo(String.valueOf(sku.getId()));
        log.warn("库存预警 skuId={} available={} threshold={}", sku.getId(), available, threshold);
        countStockAlert(ALERT_LEVEL_WARNING);
        // P1-1：预警事件与 t_stock_warning 落库同事务提交（所有调用方均在 @Transactional 内），
        // 由 outbox relay 至少一次投递，消费端按 skuId/bizNo 幂等。
        // R4-25：同一 SKU 生命周期内会多轮跌破阈值，outbox 键必须随每次预警唯一——
        // 追加本轮预警流水 id（消息体 bizNo 仍是裸 skuId，语义/排查键不变）。
        outboxPublisher.publish(MqTopics.STOCK_WARNING, null, event,
                sku.getId() + ":" + warning.getId());
    }

    private void driveSpuStatus(ProductSku changedSku, long available) {
        ProductSpu spu = spuMapper.selectById(changedSku.getSpuId());
        if (spu == null || spu.getStatus() == null) {
            return;
        }
        int current = spu.getStatus();
        if (available == 0 && current == 3) {
            // 在售中可售归零：需同一 SPU 下所有 SKU 可售都为 0 才整体售罄下架
            Long otherAvailable = skuMapper.sumAvailableExclude(spu.getId(), changedSku.getId());
            if (otherAvailable != null && otherAvailable > 0) {
                return;
            }
            int rows = spuMapper.updateStatusIf(spu.getId(), 3, 5);
            if (rows > 0) {
                syncSkuStatus(spu.getId(), 5);
                spuDetailCache.evict(spu.getId());
                countStockAlert(ALERT_LEVEL_SOLD_OUT);
                log.info("商品售罄自动下架 spuId={}", spu.getId());
            }
        } else if (available > 0 && current == 5) {
            // 补货/回库后可售恢复：售罄态自动上架
            int rows = spuMapper.updateStatusIf(spu.getId(), 5, 3);
            if (rows > 0) {
                syncSkuStatus(spu.getId(), 3);
                spuDetailCache.evict(spu.getId());
                countStockAlert(ALERT_LEVEL_RESTOCK);
                log.info("商品补货自动上架 spuId={}", spu.getId());
            }
        }
    }

    /**
     * O6 库存预警族计数：唯一标签 level（WARNING/SOLD_OUT/RESTOCK 受控常量），
     * 不带 skuId/spuId/merchantId 等高基数值；无注册表时空转。
     */
    private void countStockAlert(String level) {
        if (meterRegistry != null) {
            meterRegistry.counter(STOCK_ALERT_TOTAL, "level", level).increment();
        }
    }

    private void syncSkuStatus(Long spuId, int status) {
        skuMapper.update(null, new LambdaUpdateWrapper<ProductSku>()
                .eq(ProductSku::getSpuId, spuId)
                .set(ProductSku::getStatus, status));
    }

    private ProductSku requireSku(Long skuId) {
        ProductSku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "SKU 不存在：" + skuId);
        }
        return sku;
    }

    private int resolveType(Integer stockType) {
        return stockType == null ? StockTypes.NORMAL.getCode() : StockTypes.fromCode(stockType).getCode();
    }

    // ------------------------------------------------------------------
    // MQ 事件处理（消费流水与业务在同一事务，eventId 幂等）
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleOrderCreated(OrderCreatedEvent event) {
        if (!tryRecord(event.getEventId(), MqTopics.ORDER_CREATED, event.getOrderNo())) {
            return;
        }
        // B5：预售单（定金单与尾款单均然）下单不锁库存（MQ 二道）。
        // 消费记录照写保证幂等；不写流水、不动库存，库存动作自定金支付成功开始。
        if (Integer.valueOf(OrderTypes.PRESALE).equals(event.getOrderType())) {
            log.info("预售单 ORDER_CREATED 库存 no-op orderNo={} parentOrderNo={}",
                    event.getOrderNo(), event.getParentOrderNo());
            return;
        }
        int stockType = mapOrderTypeToStockType(event.getOrderType());
        for (OrderItemMessage item : event.getItems()) {
            StockItemCommand cmd = StockItemCommand.builder()
                    .skuId(item.getSkuId())
                    .qty(item.getQty())
                    .stockType(stockType)
                    .activityId(item.getSeckillActivityId() != null ? item.getSeckillActivityId()
                            : item.getPresaleActivityId())
                    .build();
            ProductStockLog exist = stockLogMapper.selectByUk(event.getOrderNo(), item.getSkuId(), stockType);
            if (exist != null) {
                continue;
            }
            doLock(event.getOrderNo(), cmd, stockType);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleOrderPaid(PaymentSucceededEvent event) {
        if (!tryRecord(event.getEventId(), MqTopics.ORDER_PAID, event.getOrderNo())) {
            return;
        }
        // B5 预售两阶段分流；orderType 缺失的历史消息落普通 confirm 路径（C31 兼容口径）
        if (Integer.valueOf(OrderTypes.PRESALE).equals(event.getOrderType())) {
            // ORDER_PAID 事件体不含 items，经订单内网查明细
            OrderDTO order = requireOrder(event.getOrderNo());
            if (Boolean.TRUE.equals(event.getPresaleFinalStage())) {
                linkPresaleFinal(event.getOrderNo(), order);
            } else {
                deductPresaleDeposit(event.getOrderNo(), order);
            }
            return;
        }
        // ORDER_PAID 事件体不含 items，以本域锁定流水为准逐笔 confirm
        List<ProductStockLog> lockedLogs = stockLogMapper.selectLockedByOrder(event.getOrderNo());
        for (ProductStockLog stockLog : lockedLogs) {
            confirmExisting(stockLog);
        }
    }

    /**
     * B5 定金支付：presale_stock -= qty、occupied_stock += qty，插 type=2 status=1 流水。
     * uk(order_no, sku_id, type) 幂等：重复支付回调只扣一次。
     */
    private void deductPresaleDeposit(String orderNo, OrderDTO order) {
        for (OrderItemDTO item : order.getItems()) {
            int qty = nz(item.getQty());
            lockTemplate.execute(LOCK_PREFIX + item.getSkuId(), () -> {
                ProductStockLog exist = stockLogMapper.selectByUk(
                        orderNo, item.getSkuId(), StockTypes.PRESALE.getCode());
                if (exist != null) {
                    // 定金流水已存在（任何状态）：幂等成功，不重复扣
                    return null;
                }
                ProductSku sku = requireSku(item.getSkuId());
                int rows = skuMapper.deductPresaleStock(item.getSkuId(), qty);
                if (rows == 0) {
                    throw new BizException(ErrorCode.STOCK_NOT_ENOUGH,
                            "SKU[" + item.getSkuId() + "] 预售库存不足，需要 " + qty + " 件");
                }
                ProductStockLog stockLog = new ProductStockLog();
                stockLog.setOrderNo(orderNo);
                stockLog.setSkuId(item.getSkuId());
                stockLog.setSpuId(sku.getSpuId());
                stockLog.setMerchantId(sku.getMerchantId());
                stockLog.setType(StockTypes.PRESALE.getCode());
                stockLog.setQty(qty);
                stockLog.setStatus(StockLockStatuses.DEDUCTED.getCode());
                stockLogMapper.insert(stockLog);
                return null;
            });
        }
    }

    /**
     * B5 尾款支付：插尾款流水（type=2 status=1，ref_order_no=定金单号），
     * occupied 不再变动；发货时按尾款流水统一出账。UK + ref 双重防重。
     */
    private void linkPresaleFinal(String finalOrderNo, OrderDTO finalOrder) {
        for (OrderItemDTO item : finalOrder.getItems()) {
            int qty = nz(item.getQty());
            lockTemplate.execute(LOCK_PREFIX + item.getSkuId(), () -> {
                ProductStockLog self = stockLogMapper.selectByUk(
                        finalOrderNo, item.getSkuId(), StockTypes.PRESALE.getCode());
                if (self != null) {
                    // 尾款流水已存在：幂等成功
                    return null;
                }
                ProductStockLog deposit = stockLogMapper.selectPresaleDeposit(item.getSkuId(), finalOrderNo);
                if (deposit == null) {
                    // 同单号两支付（定金/尾款共用单号）口径下，定金流水即本单号且已承担占用：
                    // selectByUk 已覆盖；走到这里说明定金尚未扣减或已被关联/回补，抛冲突由 Broker 重试
                    throw new BizException(ErrorCode.CONFLICT,
                            "尾款关联不到定金扣减流水，稍后重试：" + finalOrderNo + " skuId=" + item.getSkuId());
                }
                if (deposit.getStatus() != StockLockStatuses.DEDUCTED.getCode()) {
                    throw new BizException(ErrorCode.CONFLICT,
                            "定金流水状态为 " + deposit.getStatus() + "，尾款不能关联：" + deposit.getOrderNo());
                }
                ProductSku sku = requireSku(item.getSkuId());
                ProductStockLog finalLog = new ProductStockLog();
                finalLog.setOrderNo(finalOrderNo);
                finalLog.setRefOrderNo(deposit.getOrderNo());
                finalLog.setSkuId(item.getSkuId());
                finalLog.setSpuId(sku.getSpuId());
                finalLog.setMerchantId(sku.getMerchantId());
                finalLog.setType(StockTypes.PRESALE.getCode());
                finalLog.setQty(qty);
                finalLog.setStatus(StockLockStatuses.DEDUCTED.getCode());
                stockLogMapper.insert(finalLog);
                log.info("预售尾款关联定金 finalOrderNo={} depositOrderNo={} skuId={}",
                        finalOrderNo, deposit.getOrderNo(), item.getSkuId());
                return null;
            });
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleOrderCancelled(OrderCancelledEvent event) {
        if (!tryRecord(event.getEventId(), MqTopics.ORDER_CANCELLED, event.getOrderNo())) {
            return;
        }
        // B5 预售取消（尾款超时/违约）：对 status=1 的预售定金流水回补 presale_stock、
        // 转出 occupied、status → 5；尾款单自身无定金流水，安全跳过。
        if (Integer.valueOf(OrderTypes.PRESALE).equals(event.getOrderType())) {
            if (event.getItems() != null && !event.getItems().isEmpty()) {
                for (OrderItemMessage item : event.getItems()) {
                    ProductStockLog depositLog = stockLogMapper.selectByUk(
                            event.getOrderNo(), item.getSkuId(), StockTypes.PRESALE.getCode());
                    if (depositLog != null) {
                        returnPresaleDeposit(depositLog);
                    }
                }
            } else {
                List<ProductStockLog> logs =
                        stockLogMapper.selectPresaleDeductedByOrder(event.getOrderNo());
                for (ProductStockLog depositLog : logs) {
                    returnPresaleDeposit(depositLog);
                }
            }
            return;
        }
        if (event.getItems() != null && !event.getItems().isEmpty()) {
            for (OrderItemMessage item : event.getItems()) {
                ProductStockLog stockLog =
                        stockLogMapper.selectLockedByOrderAndSku(event.getOrderNo(), item.getSkuId());
                if (stockLog != null) {
                    releaseExisting(stockLog);
                }
                // 无锁定流水视为已 confirm/已释放，幂等跳过
            }
        } else {
            List<ProductStockLog> lockedLogs = stockLogMapper.selectLockedByOrder(event.getOrderNo());
            for (ProductStockLog stockLog : lockedLogs) {
                releaseExisting(stockLog);
            }
        }
    }

    /**
     * B5 预售定金回补（尾款违约）：occupied -= qty、presale_stock += qty，流水 1 → 5。
     * 全部条件更新带行级守卫，0 行抛冲突由 Broker 重试；status=5 重复事件幂等。
     */
    private void returnPresaleDeposit(ProductStockLog stockLog) {
        if (stockLog.getStatus() == StockLockStatuses.PRESALE_RECOVER.getCode()) {
            return;
        }
        if (stockLog.getStatus() != StockLockStatuses.DEDUCTED.getCode()
                || stockLog.getType() != StockTypes.PRESALE.getCode()) {
            throw new BizException(ErrorCode.CONFLICT,
                    "预售流水状态为 " + stockLog.getStatus() + "，不能回补：" + stockLog.getOrderNo());
        }
        int rows = skuMapper.returnPresaleStock(stockLog.getSkuId(), stockLog.getQty());
        if (rows == 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "占用库存数据异常，预售回补失败");
        }
        if (stockLogMapper.markPresaleRecover(stockLog.getId()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "库存流水并发冲突，预售回补失败");
        }
        log.info("预售尾款违约回补预售池 orderNo={} skuId={} qty={}",
                stockLog.getOrderNo(), stockLog.getSkuId(), stockLog.getQty());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleOrderShipped(OrderShippedEvent event) {
        if (!tryRecord(event.getEventId(), MqTopics.ORDER_SHIPPED, event.getOrderNo())) {
            return;
        }
        if (event.getItems() != null && !event.getItems().isEmpty()) {
            for (OrderItemMessage item : event.getItems()) {
                ProductStockLog stockLog =
                        stockLogMapper.selectByOrderAndSku(event.getOrderNo(), item.getSkuId());
                if (stockLog == null) {
                    // 扣减事件可能尚未消费（乱序）：抛冲突交 Broker 重试，禁止静默吞单
                    throw new BizException(ErrorCode.CONFLICT,
                            "发货事件找不到库存流水，稍后重试：" + event.getOrderNo()
                                    + " skuId=" + item.getSkuId());
                }
                shipOutExisting(stockLog);
            }
        } else {
            List<ProductStockLog> logs = stockLogMapper.selectByOrder(event.getOrderNo());
            for (ProductStockLog stockLog : logs) {
                shipOutExisting(stockLog);
            }
        }
    }

    /**
     * B13 发货出账：仅 status=1 → 4（occupied_stock -= qty，行级守卫）；
     * status=4 重复发货消息零变化；0/2/3/5 抛冲突（可重试，由对账/人工兜底）。
     */
    private void shipOutExisting(ProductStockLog stockLog) {
        int status = stockLog.getStatus();
        if (status == StockLockStatuses.SHIPPED_ACCOUNTED.getCode()) {
            return;
        }
        if (status != StockLockStatuses.DEDUCTED.getCode()) {
            throw new BizException(ErrorCode.CONFLICT,
                    "库存流水状态为 " + status + "，不能发货出账（仅已扣减可出账）："
                            + stockLog.getOrderNo() + " skuId=" + stockLog.getSkuId());
        }
        int rows = skuMapper.shipOutStock(stockLog.getSkuId(), stockLog.getQty());
        if (rows == 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "占用库存数据异常，发货出账失败");
        }
        if (stockLogMapper.updateStatusIf(stockLog.getId(),
                StockLockStatuses.DEDUCTED.getCode(),
                StockLockStatuses.SHIPPED_ACCOUNTED.getCode()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "库存流水并发冲突，发货出账失败");
        }
    }

    /**
     * P1-1 对账补偿入口：按订单补出账，仅处理 status=1 流水（1→4），
     * 4/0/2/3/5 一律跳过，保证对账 Job 重复调用安全。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void shipOutExisting(String orderNo) {
        List<ProductStockLog> logs = stockLogMapper.selectByOrder(orderNo);
        for (ProductStockLog stockLog : logs) {
            if (stockLog.getStatus() != null
                    && stockLog.getStatus() == StockLockStatuses.DEDUCTED.getCode()) {
                shipOutExisting(stockLog);
            }
        }
    }

    /**
     * P1-1 对账补偿入口：按订单回补全部 status=1 预售定金流水（尾款违约口径）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void returnPresaleDeposit(String orderNo) {
        List<ProductStockLog> logs = stockLogMapper.selectPresaleDeductedByOrder(orderNo);
        for (ProductStockLog stockLog : logs) {
            returnPresaleDeposit(stockLog);
        }
    }

    private OrderDTO requireOrder(String orderNo) {
        OrderDTO order = FeignResults.unwrap(orderClient.getByOrderNo(orderNo));
        if (order == null || order.getItems() == null || order.getItems().isEmpty()) {
            // 跨服务复制延迟/聚合未落库：可恢复错误，交 Broker 重试
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "支付事件找不到订单明细：" + orderNo);
        }
        return order;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAftersaleChanged(AftersaleChangedEvent event) {
        if (!tryRecord(event.getEventId(), MqTopics.AFTERSALE_CHANGED, event.getAftersaleNo())) {
            return;
        }
        // 仅处理：已完成 且 退货退款/换货
        if (!Integer.valueOf(AftersaleStatuses.FINISHED).equals(event.getNewStatus())) {
            return;
        }
        if (event.getType() != AftersaleTypes.RETURN_REFUND
                && event.getType() != AftersaleTypes.EXCHANGE) {
            return;
        }
        int reason;
        if (event.getType() == AftersaleTypes.EXCHANGE) {
            // 换货回库，回可售（换出品按新订单链路锁定/扣减）
            reason = StockReturnReasons.EXCHANGE.getCode();
        } else if (Integer.valueOf(ResponsibilitySide.MERCHANT).equals(event.getResponsibilitySide())) {
            // 商家责任（质量问题）入残次仓
            reason = StockReturnReasons.QUALITY.getCode();
        } else {
            // 买家责任 / 运费险：商品本身无质量问题，回可售
            reason = StockReturnReasons.BUYER.getCode();
        }
        StockReturnCommand cmd = StockReturnCommand.builder()
                .orderNo(event.getOrderNo())
                .reason(reason)
                .items(event.getItems().stream()
                        .map(i -> StockItemCommand.builder().skuId(i.getSkuId()).qty(i.getQty()).build())
                        .toList())
                .build();
        returnStock(cmd);
    }

    /**
     * R4-26：消费幂等落库。eventId 优先取消息体信封字段（BaseEvent 构造即随机 UUID，活路径
     * 恒非空）；消息体缺失/空白（历史无信封消息、非 JSON 载荷）时回退框架在消费线程绑定的
     * 归一化 eventId（含 EventNormalizer 的 noid 合成兜底），避免 null eventId 写库报错或
     * 多条消息共用空键。非 MQ 线程调用且无事件 ID 属编程错误，直接 fail-fast。
     */
    private boolean tryRecord(String eventId, String topic, String bizNo) {
        String resolved = (eventId != null && !eventId.isBlank())
                ? eventId : MqConsumeContext.currentEventId();
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException("消费事件 eventId 为空且无 MQ 上下文，topic=" + topic);
        }
        return consumeRecordMapper.tryInsert(idGenerator.nextId(), resolved, topic, bizNo) > 0;
    }

    /**
     * 订单类型 → 库存类型映射：秒杀 2→3、拼团 3→4、预售 4→2，其余按普通 1。
     */
    static int mapOrderTypeToStockType(Integer orderType) {
        if (orderType == null) {
            return StockTypes.NORMAL.getCode();
        }
        return switch (orderType) {
            case 2 -> StockTypes.SECKILL.getCode();
            case 3 -> StockTypes.GROUPBUY.getCode();
            case 4 -> StockTypes.PRESALE.getCode();
            default -> StockTypes.NORMAL.getCode();
        };
    }
}
