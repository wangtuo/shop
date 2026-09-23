package com.shop.product.stock.service.impl;

import com.shop.api.aftersale.dto.AftersaleItemMessage;
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
import com.shop.api.product.enums.StockTypes;
import com.shop.api.product.event.StockWarningEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.entity.ProductSpu;
import com.shop.product.goods.mapper.ProductSkuMapper;
import com.shop.product.goods.mapper.ProductSpuMapper;
import com.shop.product.goods.support.SpuDetailCache;
import com.shop.product.mq.MqConsumeRecordMapper;
import com.shop.product.stock.entity.ProductStockLog;
import com.shop.product.stock.mapper.ProductStockLogMapper;
import com.shop.product.stock.mapper.StockWarningMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存 TCC 三态流转、超卖防护、幂等、预警与自动售罄/补货上架、MQ 事件路由全路径测试。
 */
@ExtendWith(MockitoExtension.class)
class StockServiceImplTest {

    @Mock
    private ProductSkuMapper skuMapper;
    @Mock
    private ProductSpuMapper spuMapper;
    @Mock
    private ProductStockLogMapper stockLogMapper;
    @Mock
    private StockWarningMapper warningMapper;
    @Mock
    private MqConsumeRecordMapper consumeRecordMapper;
    @Mock
    private com.shop.framework.id.IdGenerator idGenerator;
    @Mock
    private DistributedLockTemplate lockTemplate;
    @Mock
    private OutboxPublisher outboxPublisher;
    @Mock
    private SpuDetailCache spuDetailCache;
    @Mock
    private OrderClient orderClient;

    @InjectMocks
    private StockServiceImpl stockService;

    private static final long SKU_ID = 100L;
    private static final long SPU_ID = 200L;
    private static final long MERCHANT_ID = 7L;
    private static final String ORDER_NO = "260916010001000001";

    @BeforeAll
    static void initLambdaCache() {
        // LambdaUpdateWrapper 在纯单测里需要实体表信息缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProductSku.class);
        TableInfoHelper.initTableInfo(assistant, ProductSpu.class);
    }

    @BeforeEach
    void setUp() {
        // 热点 SKU 锁：直接执行临界区
        lenient().when(lockTemplate.execute(anyString(), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        MqConsumeContext.clear();
    }

    // ---- helpers ----

    private ProductSku sku(long available, long threshold, int status) {
        ProductSku sku = new ProductSku();
        sku.setId(SKU_ID);
        sku.setSpuId(SPU_ID);
        sku.setMerchantId(MERCHANT_ID);
        sku.setAvailableStock(available);
        sku.setLockedStock(0L);
        sku.setOccupiedStock(0L);
        sku.setDefectStock(0L);
        sku.setWarnThreshold(threshold);
        sku.setStatus(status);
        return sku;
    }

    private ProductSpu spu(int status) {
        ProductSpu spu = new ProductSpu();
        spu.setId(SPU_ID);
        spu.setMerchantId(MERCHANT_ID);
        spu.setStatus(status);
        spu.setVersion(0);
        return spu;
    }

    private ProductStockLog log(int status) {
        ProductStockLog log = new ProductStockLog();
        log.setId(555L);
        log.setOrderNo(ORDER_NO);
        log.setSkuId(SKU_ID);
        log.setSpuId(SPU_ID);
        log.setMerchantId(MERCHANT_ID);
        log.setType(StockTypes.NORMAL.getCode());
        log.setQty(10);
        log.setStatus(status);
        return log;
    }

    private StockLockCommand lockCmd(Integer stockType) {
        return StockLockCommand.builder()
                .orderNo(ORDER_NO)
                .items(List.of(StockItemCommand.builder()
                        .skuId(SKU_ID).qty(10).stockType(stockType).build()))
                .build();
    }

    // ---------------- TCC-try ----------------

    @Test
    void lockStock_库存充足_可售转锁定且无预警() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(90, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));

        stockService.lockStock(lockCmd(null));

        verify(skuMapper).lockStock(SKU_ID, 10);
        ArgumentCaptor<ProductStockLog> captor = ArgumentCaptor.forClass(ProductStockLog.class);
        verify(stockLogMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getStatus());
        verify(warningMapper, never()).insert(any());
        verify(spuMapper, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
    }

    @Test
    void lockStock_库存不足_抛STOCK_NOT_ENOUGH且不写流水() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(5, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> stockService.lockStock(lockCmd(null)));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), ex.getCode());
        verify(stockLogMapper, never()).insert(any());
    }

    @Test
    void lockStock_已有锁定流水_幂等成功不重复扣库存() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(log(0));

        stockService.lockStock(lockCmd(1));

        verify(skuMapper, never()).lockStock(anyLong(), anyInt());
    }

    @Test
    void lockStock_已释放流水重复请求_同样幂等跳过() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(log(2));

        stockService.lockStock(lockCmd(1));

        verify(skuMapper, never()).lockStock(anyLong(), anyInt());
    }

    @Test
    void lockStock_可售低于阈值_落预警并发STOCK_WARNING事件() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(5, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));
        stubWarningArmed(555L);

        stockService.lockStock(lockCmd(null));

        verify(warningMapper).insert(any());
        ArgumentCaptor<StockWarningEvent> captor = ArgumentCaptor.forClass(StockWarningEvent.class);
        // R4-25：outbox 键带本轮预警流水 id，同一 SKU 多轮预警不再撞 uk_topic_tag_bizkey
        verify(outboxPublisher).publish(eq(MqTopics.STOCK_WARNING), isNull(), captor.capture(),
                eq(SKU_ID + ":555"));
        StockWarningEvent event = captor.getValue();
        assertEquals(SKU_ID, event.getSkuId());
        assertEquals(5L, event.getAvailable());
        assertEquals(10L, event.getThreshold());
        // 消息体 bizNo 仍是裸 skuId
        assertEquals(String.valueOf(SKU_ID), event.getBizNo());
    }

    @Test
    void lockStock_持续低库存第二次变动_预警武装未复位_不再登记预警() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(5, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));
        // CAS 抢占落败：上一轮已发/并发他事务已发
        when(skuMapper.casLowStockAlertOn(SKU_ID)).thenReturn(0);

        stockService.lockStock(lockCmd(null));

        verify(warningMapper, never()).insert(any());
        verify(outboxPublisher, never()).publish(eq(MqTopics.STOCK_WARNING), any(), any(), anyString());
    }

    @Test
    void 低库存恢复后再次跌破_重新武装并再发一轮_两轮outbox键不同() {
        UserContext.set(LoginUser.builder().userId(99L).userType(1).merchantId(MERCHANT_ID).build());
        // 第一轮：跌破阈值，武装抢占成功发预警（流水 id 555）
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(5, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));
        stubWarningArmed(555L);

        stockService.lockStock(lockCmd(null));
        verify(outboxPublisher).publish(eq(MqTopics.STOCK_WARNING), isNull(), any(),
                eq(SKU_ID + ":555"));

        // 补货越过阈值：CAS 复位武装，不发预警
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(20, 10, 5));
        when(skuMapper.replenish(SKU_ID, 15)).thenReturn(1);
        stockService.replenish(SKU_ID, 15);
        verify(skuMapper).casLowStockAlertOff(SKU_ID);

        // 再次跌破：新预警流水 id 666，outbox 键不同于首轮
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(8, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(skuMapper.casLowStockAlertOn(SKU_ID)).thenReturn(1);
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, com.shop.product.stock.entity.StockWarning.class).setId(666L);
            return 1;
        }).when(warningMapper).insert(any());

        stockService.lockStock(lockCmd(null));
        verify(outboxPublisher).publish(eq(MqTopics.STOCK_WARNING), isNull(), any(),
                eq(SKU_ID + ":666"));
    }

    /** R4-25：模拟低库存武装 CAS 获胜（0→1）+ MP 雪花回填预警流水 id。 */
    private void stubWarningArmed(long warningId) {
        when(skuMapper.casLowStockAlertOn(SKU_ID)).thenReturn(1);
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            inv.getArgument(0, com.shop.product.stock.entity.StockWarning.class).setId(warningId);
            return 1;
        }).when(warningMapper).insert(any());
    }

    @Test
    void lockStock_可售归零且在售_全SPU无库存_自动售罄下架() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(0, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));
        when(skuMapper.sumAvailableExclude(SPU_ID, SKU_ID)).thenReturn(0L);
        when(spuMapper.updateStatusIf(SPU_ID, 3, 5)).thenReturn(1);
        stubWarningArmed(555L);

        stockService.lockStock(lockCmd(null));

        verify(spuMapper).updateStatusIf(SPU_ID, 3, 5);
        verify(spuDetailCache).evict(SPU_ID);
        verify(warningMapper).insert(any());
    }

    @Test
    void lockStock_可售归零但其他SKU有库存_不触发售罄() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(0, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));
        when(skuMapper.sumAvailableExclude(SPU_ID, SKU_ID)).thenReturn(3L);

        stockService.lockStock(lockCmd(null));

        verify(spuMapper, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
    }

    // ---------------- O6 库存预警指标 shop_stock_alert_total ----------------

    private Counter alertCounter(SimpleMeterRegistry registry, String level) {
        return registry.find("shop_stock_alert_total").tag("level", level).counter();
    }

    private void assertOnlyLevelTag(Counter counter) {
        Set<String> tagKeys = counter.getId().getTags().stream()
                .map(t -> t.getKey()).collect(Collectors.toSet());
        assertEquals(Set.of("level"), tagKeys, "库存预警计数器只允许 level 标签，严禁 skuId/spuId/merchantId");
    }

    @Test
    void 库存预警_WARNING计数且标签仅level不含skuId() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(stockService, "meterRegistry", registry);

        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(5, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));
        stubWarningArmed(555L);

        stockService.lockStock(lockCmd(null));

        Counter warning = alertCounter(registry, "WARNING");
        assertNotNull(warning);
        assertEquals(1.0, warning.count());
        assertOnlyLevelTag(warning);
        // 可售未归零，不产生售罄/补货事件
        assertNull(alertCounter(registry, "SOLD_OUT"));
        assertNull(alertCounter(registry, "RESTOCK"));
    }

    @Test
    void 自动售罄与补货上架_SOLD_OUT与RESTOCK各计数一次() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(stockService, "meterRegistry", registry);
        UserContext.set(LoginUser.builder().userId(99L).userType(1).merchantId(MERCHANT_ID).build());

        // 阶段一：可售归零、全 SPU 无货 → WARNING + SOLD_OUT
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID))
                .thenReturn(sku(0, 10, 3))
                .thenReturn(sku(0, 10, 3))
                .thenReturn(sku(20, 10, 5))
                .thenReturn(sku(20, 10, 5));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3)).thenReturn(spu(5));
        when(skuMapper.sumAvailableExclude(SPU_ID, SKU_ID)).thenReturn(0L);
        when(spuMapper.updateStatusIf(SPU_ID, 3, 5)).thenReturn(1);
        when(skuMapper.replenish(SKU_ID, 20)).thenReturn(1);
        when(spuMapper.updateStatusIf(SPU_ID, 5, 3)).thenReturn(1);
        stubWarningArmed(555L);

        stockService.lockStock(lockCmd(null));
        // 阶段二：补货可售恢复且超阈值（不再发 WARNING）→ RESTOCK
        stockService.replenish(SKU_ID, 20);

        Counter warning = alertCounter(registry, "WARNING");
        Counter soldOut = alertCounter(registry, "SOLD_OUT");
        Counter restock = alertCounter(registry, "RESTOCK");
        assertNotNull(warning);
        assertNotNull(soldOut);
        assertNotNull(restock);
        assertEquals(1.0, warning.count());
        assertEquals(1.0, soldOut.count());
        assertEquals(1.0, restock.count());
        assertOnlyLevelTag(warning);
        assertOnlyLevelTag(soldOut);
        assertOnlyLevelTag(restock);
    }

    // ---------------- TCC-confirm ----------------

    @Test
    void confirmDeduct_锁定中_锁定转占用() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(log(0));
        when(skuMapper.confirmDeduct(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.updateStatusIf(555L, 0, 1)).thenReturn(1);

        stockService.confirmDeduct(new StockDeductCommand(ORDER_NO, 1,
                List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).stockType(1).build())));

        verify(skuMapper).confirmDeduct(SKU_ID, 10);
        verify(stockLogMapper).updateStatusIf(555L, 0, 1);
    }

    @Test
    void confirmDeduct_已扣减_幂等成功() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(log(1));

        stockService.confirmDeduct(new StockDeductCommand(ORDER_NO, 1,
                List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).stockType(1).build())));

        verify(skuMapper, never()).confirmDeduct(anyLong(), anyInt());
    }

    @Test
    void confirmDeduct_已释放_抛冲突() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(log(2));

        BizException ex = assertThrows(BizException.class, () -> stockService.confirmDeduct(
                new StockDeductCommand(ORDER_NO, 1,
                        List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).stockType(1).build()))));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void confirmDeduct_流水不存在_抛冲突() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);

        assertThrows(BizException.class, () -> stockService.confirmDeduct(
                new StockDeductCommand(ORDER_NO, 1,
                        List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).stockType(1).build()))));
    }

    // ---------------- TCC-cancel ----------------

    @Test
    void releaseStock_锁定中_回可售() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(log(0));
        when(skuMapper.releaseStock(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.updateStatusIf(555L, 0, 2)).thenReturn(1);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(90, 10, 3));
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));

        stockService.releaseStock(new StockReleaseCommand(ORDER_NO, 1,
                List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).stockType(1).build())));

        verify(skuMapper).releaseStock(SKU_ID, 10);
        verify(stockLogMapper).updateStatusIf(555L, 0, 2);
    }

    @Test
    void releaseStock_已释放_幂等成功() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(log(2));

        stockService.releaseStock(new StockReleaseCommand(ORDER_NO, 1,
                List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).stockType(1).build())));

        verify(skuMapper, never()).releaseStock(anyLong(), anyInt());
    }

    @Test
    void releaseStock_已扣减_抛冲突需走售后() {
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(log(1));

        assertThrows(BizException.class, () -> stockService.releaseStock(
                new StockReleaseCommand(ORDER_NO, 1,
                        List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).stockType(1).build()))));
    }

    // ---------------- 售后回库 ----------------

    @Test
    void returnStock_买家责任_回可售且售罄自动上架() {
        when(stockLogMapper.selectDeductedByOrderAndSku(ORDER_NO, SKU_ID)).thenReturn(log(1));
        when(skuMapper.returnToAvailable(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.markReturned(555L, 1)).thenReturn(1);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(10, 10, 5));
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(5));
        when(spuMapper.updateStatusIf(SPU_ID, 5, 3)).thenReturn(1);

        stockService.returnStock(StockReturnCommand.builder().orderNo(ORDER_NO).reason(1)
                .items(List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).build())).build());

        verify(skuMapper).returnToAvailable(SKU_ID, 10);
        verify(skuMapper, never()).returnToDefect(anyLong(), anyInt());
        verify(stockLogMapper).markReturned(555L, 1);
        verify(spuMapper).updateStatusIf(SPU_ID, 5, 3);
        verify(spuDetailCache).evict(SPU_ID);
    }

    @Test
    void returnStock_质量问题_入残次不回可售不自动上架() {
        when(stockLogMapper.selectDeductedByOrderAndSku(ORDER_NO, SKU_ID)).thenReturn(log(1));
        when(skuMapper.returnToDefect(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.markReturned(555L, 2)).thenReturn(1);

        stockService.returnStock(StockReturnCommand.builder().orderNo(ORDER_NO).reason(2)
                .items(List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).build())).build());

        verify(skuMapper).returnToDefect(SKU_ID, 10);
        verify(skuMapper, never()).returnToAvailable(anyLong(), anyInt());
        verify(spuMapper, never()).updateStatusIf(anyLong(), anyInt(), anyInt());
        verify(warningMapper, never()).insert(any());
    }

    @Test
    void returnStock_已回库_幂等成功() {
        when(stockLogMapper.selectDeductedByOrderAndSku(ORDER_NO, SKU_ID)).thenReturn(log(3));

        stockService.returnStock(StockReturnCommand.builder().orderNo(ORDER_NO).reason(1)
                .items(List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).build())).build());

        verify(skuMapper, never()).returnToAvailable(anyLong(), anyInt());
    }

    @Test
    void returnStock_无已扣减流水_抛冲突() {
        when(stockLogMapper.selectDeductedByOrderAndSku(ORDER_NO, SKU_ID)).thenReturn(null);

        assertThrows(BizException.class, () -> stockService.returnStock(
                StockReturnCommand.builder().orderNo(ORDER_NO).reason(1)
                        .items(List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).build())).build()));
    }

    // ---------------- 查询/可售 ----------------

    @Test
    void saleable_上架且库存足_返回true() {
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(20, 10, 3));
        assertTrue(stockService.saleable(SKU_ID, 20));
    }

    @Test
    void saleable_非上架或库存不足_返回false() {
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(0, 10, 5));
        assertFalse(stockService.saleable(SKU_ID, 1));
        ProductSku draft = sku(100, 10, 0);
        when(skuMapper.selectById(101L)).thenReturn(draft);
        assertFalse(stockService.saleable(101L, 1));
    }

    @Test
    void saleable_非法参数_抛异常() {
        assertThrows(BizException.class, () -> stockService.saleable(SKU_ID, 0));
    }

    @Test
    void listSkus_空入参_返回空集合() {
        assertTrue(stockService.listSkus(List.of()).isEmpty());
    }

    @Test
    void getSku_不存在_抛NOT_FOUND() {
        when(skuMapper.selectById(SKU_ID)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> stockService.getSku(SKU_ID));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void getSku_正常_返回聚合DTO() {
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(88, 10, 3));
        SkuDTO dto = stockService.getSku(SKU_ID);
        assertEquals(SKU_ID, dto.getSkuId());
        assertEquals(88L, dto.getAvailableStock());
        assertEquals(3, dto.getStatus());
    }

    // ---------------- 补货 ----------------

    @Test
    void 补货_店主为售罄SKU补货_自动重新上架() {
        UserContext.set(LoginUser.builder().userId(99L).userType(1).merchantId(MERCHANT_ID).build());
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(20, 10, 5));
        when(skuMapper.replenish(SKU_ID, 20)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(5));
        when(spuMapper.updateStatusIf(SPU_ID, 5, 3)).thenReturn(1);

        stockService.replenish(SKU_ID, 20);

        verify(skuMapper).replenish(SKU_ID, 20);
        verify(spuMapper).updateStatusIf(SPU_ID, 5, 3);
        verify(spuDetailCache).evict(SPU_ID);
        verify(warningMapper, never()).insert(any());
    }

    @Test
    void 补货_非本店商户_拒绝且不动库存() {
        UserContext.set(LoginUser.builder().userId(99L).userType(1).merchantId(999L).build());
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(20, 10, 5));

        assertThrows(BizException.class, () -> stockService.replenish(SKU_ID, 20));
        verify(skuMapper, never()).replenish(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 补货_非正数量_抛参数异常() {
        assertThrows(BizException.class, () -> stockService.replenish(SKU_ID, 0));
        verify(skuMapper, never()).replenish(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ---------------- MQ 事件 ----------------

    private OrderCreatedEvent orderCreatedEvent(String eventId, int orderType) {
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderNo(ORDER_NO)
                .orderType(orderType)
                .items(List.of(OrderItemMessage.builder()
                        .skuId(SKU_ID).qty(10).seckillActivityId(99L).build()))
                .build();
        event.setEventId(eventId);
        return event;
    }

    @Test
    void 订单类型到库存类型映射() {
        assertEquals(1, StockServiceImpl.mapOrderTypeToStockType(1));
        assertEquals(3, StockServiceImpl.mapOrderTypeToStockType(2));
        assertEquals(4, StockServiceImpl.mapOrderTypeToStockType(3));
        assertEquals(2, StockServiceImpl.mapOrderTypeToStockType(4));
        assertEquals(1, StockServiceImpl.mapOrderTypeToStockType(5));
        assertEquals(1, StockServiceImpl.mapOrderTypeToStockType(null));
    }

    @Test
    void 消费ORDER_CREATED_秒杀订单_按秒杀库存类型锁定() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("e1"), eq(MqTopics.ORDER_CREATED), eq(ORDER_NO))).thenReturn(1);
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 3)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(90, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));

        stockService.handleOrderCreated(orderCreatedEvent("e1", 2));

        verify(skuMapper).lockStock(SKU_ID, 10);
        ArgumentCaptor<ProductStockLog> captor = ArgumentCaptor.forClass(ProductStockLog.class);
        verify(stockLogMapper).insert(captor.capture());
        assertEquals(3, captor.getValue().getType());
    }

    @Test
    void 消费ORDER_CREATED_重复eventId_直接ACK不锁库存() {
        when(consumeRecordMapper.tryInsert(anyLong(), anyString(), anyString(), anyString())).thenReturn(0);

        stockService.handleOrderCreated(orderCreatedEvent("e1", 1));

        verify(skuMapper, never()).lockStock(anyLong(), anyInt());
    }

    @Test
    void 消费ORDER_CREATED_消息体eventId空白_回退框架上下文eventId落库() {
        // R4-26：历史无信封消息 body eventId 空白时，幂等键取 MqConsumeContext 归一化 eventId
        MqConsumeContext.bind(new MqConsumeContext(
                "noid:shop_order_created:" + ORDER_NO, MqTopics.ORDER_CREATED, ORDER_NO, "rmq-1", true));
        when(consumeRecordMapper.tryInsert(anyLong(),
                eq("noid:shop_order_created:" + ORDER_NO), eq(MqTopics.ORDER_CREATED), eq(ORDER_NO)))
                .thenReturn(1);
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 3)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(90, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));

        stockService.handleOrderCreated(orderCreatedEvent("  ", 2));

        verify(skuMapper).lockStock(SKU_ID, 10);
    }

    @Test
    void 消费事件_eventId空白且无MQ上下文_failFast不写库() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stockService.handleOrderCreated(orderCreatedEvent(" ", 1)));
        assertTrue(ex.getMessage().contains("eventId"));
        verify(consumeRecordMapper, never()).tryInsert(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void 消费ORDER_PAID_按锁定流水逐笔confirm() {
        PaymentSucceededEvent event = PaymentSucceededEvent.builder().orderNo(ORDER_NO).build();
        event.setEventId("p1");
        when(consumeRecordMapper.tryInsert(anyLong(), eq("p1"), eq(MqTopics.ORDER_PAID), eq(ORDER_NO))).thenReturn(1);
        when(stockLogMapper.selectLockedByOrder(ORDER_NO)).thenReturn(List.of(log(0)));
        when(skuMapper.confirmDeduct(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.updateStatusIf(555L, 0, 1)).thenReturn(1);

        stockService.handleOrderPaid(event);

        verify(skuMapper).confirmDeduct(SKU_ID, 10);
    }

    @Test
    void 消费ORDER_PAID_重复eventId_跳过() {
        PaymentSucceededEvent event = PaymentSucceededEvent.builder().orderNo(ORDER_NO).build();
        event.setEventId("p2");
        when(consumeRecordMapper.tryInsert(anyLong(), eq("p2"), eq(MqTopics.ORDER_PAID), eq(ORDER_NO))).thenReturn(0);

        stockService.handleOrderPaid(event);

        verify(stockLogMapper, never()).selectLockedByOrder(anyString());
    }

    @Test
    void 消费ORDER_CANCELLED_按items释放锁定库存() {
        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .orderNo(ORDER_NO)
                .items(List.of(OrderItemMessage.builder().skuId(SKU_ID).qty(10).build()))
                .build();
        event.setEventId("c1");
        when(consumeRecordMapper.tryInsert(anyLong(), eq("c1"), eq(MqTopics.ORDER_CANCELLED), eq(ORDER_NO))).thenReturn(1);
        when(stockLogMapper.selectLockedByOrderAndSku(ORDER_NO, SKU_ID)).thenReturn(log(0));
        when(skuMapper.releaseStock(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.updateStatusIf(555L, 0, 2)).thenReturn(1);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(90, 10, 3));
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));

        stockService.handleOrderCancelled(event);

        verify(skuMapper).releaseStock(SKU_ID, 10);
    }

    @Test
    void 消费ORDER_CANCELLED_无items时按订单全量释放() {
        OrderCancelledEvent event = OrderCancelledEvent.builder().orderNo(ORDER_NO).build();
        event.setEventId("c2");
        when(consumeRecordMapper.tryInsert(anyLong(), eq("c2"), eq(MqTopics.ORDER_CANCELLED), eq(ORDER_NO))).thenReturn(1);
        when(stockLogMapper.selectLockedByOrder(ORDER_NO)).thenReturn(List.of(log(0)));
        when(skuMapper.releaseStock(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.updateStatusIf(555L, 0, 2)).thenReturn(1);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(90, 10, 3));
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));

        stockService.handleOrderCancelled(event);

        verify(skuMapper).releaseStock(SKU_ID, 10);
    }

    private AftersaleChangedEvent aftersaleEvent(String eventId, int type, int newStatus, Integer side) {
        AftersaleChangedEvent event = AftersaleChangedEvent.builder()
                .aftersaleNo("AS202609160001")
                .orderNo(ORDER_NO)
                .type(type)
                .newStatus(newStatus)
                .responsibilitySide(side)
                .items(List.of(AftersaleItemMessage.builder().skuId(SKU_ID).qty(10).build()))
                .build();
        event.setEventId(eventId);
        return event;
    }

    @Test
    void 消费售后_完成退货退款买家责任_回可售() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("a1"), eq(MqTopics.AFTERSALE_CHANGED), eq("AS202609160001"))).thenReturn(1);
        when(stockLogMapper.selectDeductedByOrderAndSku(ORDER_NO, SKU_ID)).thenReturn(log(1));
        when(skuMapper.returnToAvailable(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.markReturned(555L, 1)).thenReturn(1);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(90, 10, 3));
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));

        stockService.handleAftersaleChanged(aftersaleEvent("a1", 2, 50, 2));

        verify(skuMapper).returnToAvailable(SKU_ID, 10);
        verify(stockLogMapper).markReturned(555L, 1);
    }

    @Test
    void 消费售后_商家责任质量问题_入残次() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("a2"), eq(MqTopics.AFTERSALE_CHANGED), eq("AS202609160001"))).thenReturn(1);
        when(stockLogMapper.selectDeductedByOrderAndSku(ORDER_NO, SKU_ID)).thenReturn(log(1));
        when(skuMapper.returnToDefect(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.markReturned(555L, 2)).thenReturn(1);

        stockService.handleAftersaleChanged(aftersaleEvent("a2", 2, 50, 1));

        verify(skuMapper).returnToDefect(SKU_ID, 10);
        verify(stockLogMapper).markReturned(555L, 2);
    }

    @Test
    void 消费售后_换货完成_按换货原因回可售() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("a3"), eq(MqTopics.AFTERSALE_CHANGED), eq("AS202609160001"))).thenReturn(1);
        when(stockLogMapper.selectDeductedByOrderAndSku(ORDER_NO, SKU_ID)).thenReturn(log(1));
        when(skuMapper.returnToAvailable(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.markReturned(555L, 3)).thenReturn(1);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(90, 10, 3));
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));

        stockService.handleAftersaleChanged(aftersaleEvent("a3", 3, 50, null));

        verify(stockLogMapper).markReturned(555L, 3);
    }

    @Test
    void 消费售后_非完成状态_忽略不回库() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("a4"), eq(MqTopics.AFTERSALE_CHANGED), eq("AS202609160001"))).thenReturn(1);

        stockService.handleAftersaleChanged(aftersaleEvent("a4", 2, 40, 2));

        verify(stockLogMapper, never()).selectDeductedByOrderAndSku(anyString(), anyLong());
    }

    @Test
    void 消费售后_重复eventId_跳过() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("a5"), eq(MqTopics.AFTERSALE_CHANGED), eq("AS202609160001"))).thenReturn(0);

        stockService.handleAftersaleChanged(aftersaleEvent("a5", 2, 50, 2));

        verify(stockLogMapper, never()).selectDeductedByOrderAndSku(anyString(), anyLong());
    }

    // ==================================================================
    // B5：预售两阶段库存状态机
    // ==================================================================

    private static final String DEPOSIT_NO = "260916040007000001";
    private static final String FINAL_NO = "260916040007000002";

    private ProductSku presaleSku(long available, long occupied, long presale) {
        ProductSku sku = sku(available, 10, 3);
        sku.setOccupiedStock(occupied);
        sku.setPresaleStock(presale);
        return sku;
    }

    private ProductStockLog presaleLog(String orderNo, int status) {
        ProductStockLog log = new ProductStockLog();
        log.setId(666L);
        log.setOrderNo(orderNo);
        log.setSkuId(SKU_ID);
        log.setSpuId(SPU_ID);
        log.setMerchantId(MERCHANT_ID);
        log.setType(StockTypes.PRESALE.getCode());
        log.setQty(10);
        log.setStatus(status);
        return log;
    }

    private OrderDTO orderWithItem(String orderNo) {
        return OrderDTO.builder()
                .orderNo(orderNo)
                .items(List.of(OrderItemDTO.builder().skuId(SKU_ID).spuId(SPU_ID)
                        .merchantId(MERCHANT_ID).qty(10).build()))
                .build();
    }

    private PaymentSucceededEvent paidEvent(String eventId, String orderNo, Boolean finalStage) {
        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .orderNo(orderNo)
                .orderType(OrderTypes.PRESALE)
                .presaleFinalStage(finalStage)
                .build();
        event.setEventId(eventId);
        return event;
    }

    @Test
    void 预售下单_同步道与MQ道_四仓数量全不变且不写流水() {
        // 同步 Feign 道：orderType=4 直接幂等成功
        stockService.lockStock(StockLockCommand.builder()
                .orderNo(DEPOSIT_NO).orderType(OrderTypes.PRESALE)
                .items(List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).build()))
                .build());
        verify(skuMapper, never()).lockStock(anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(stockLogMapper, never()).insert(any());

        // MQ 二道：消费记录照写，库存 no-op（事件携带定金单号 DEPOSIT_NO）
        OrderCreatedEvent presaleCreated = OrderCreatedEvent.builder()
                .orderNo(DEPOSIT_NO).orderType(OrderTypes.PRESALE)
                .items(List.of(OrderItemMessage.builder().skuId(SKU_ID).qty(10).build()))
                .build();
        presaleCreated.setEventId("pc1");
        when(consumeRecordMapper.tryInsert(anyLong(), eq("pc1"), eq(MqTopics.ORDER_CREATED), eq(DEPOSIT_NO)))
                .thenReturn(1);
        stockService.handleOrderCreated(presaleCreated);
        verify(skuMapper, never()).lockStock(anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(stockLogMapper, never()).insert(any());
    }

    @Test
    void 定金支付_预售池扣减入占用_重复回调只动一次() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("pd1"), eq(MqTopics.ORDER_PAID), eq(DEPOSIT_NO)))
                .thenReturn(1);
        when(orderClient.getByOrderNo(DEPOSIT_NO)).thenReturn(Result.success(orderWithItem(DEPOSIT_NO)));
        when(stockLogMapper.selectByUk(DEPOSIT_NO, SKU_ID, StockTypes.PRESALE.getCode()))
                .thenReturn(null).thenReturn(presaleLog(DEPOSIT_NO, 1));
        when(skuMapper.selectById(SKU_ID)).thenReturn(presaleSku(100, 0, 20));
        when(skuMapper.deductPresaleStock(SKU_ID, 10)).thenReturn(1);

        stockService.handleOrderPaid(paidEvent("pd1", DEPOSIT_NO, false));

        verify(skuMapper).deductPresaleStock(SKU_ID, 10);
        verify(skuMapper, never()).shipOutStock(anyLong(), org.mockito.ArgumentMatchers.anyInt());
        ArgumentCaptor<ProductStockLog> captor = ArgumentCaptor.forClass(ProductStockLog.class);
        verify(stockLogMapper).insert(captor.capture());
        ProductStockLog inserted = captor.getValue();
        assertEquals(StockTypes.PRESALE.getCode(), inserted.getType());
        assertEquals(StockLockStatuses.DEDUCTED.getCode(), inserted.getStatus());
        assertEquals(10, inserted.getQty());

        // 不同 eventId 的重复支付回调（at-least-once）：UK 命中已扣减流水，库存不再动
        when(consumeRecordMapper.tryInsert(anyLong(), eq("pd2"), eq(MqTopics.ORDER_PAID), eq(DEPOSIT_NO)))
                .thenReturn(1);
        stockService.handleOrderPaid(paidEvent("pd2", DEPOSIT_NO, false));
        verify(skuMapper, org.mockito.Mockito.times(1)).deductPresaleStock(SKU_ID, 10);
    }

    @Test
    void 尾款支付_occupied不二次增加_尾款流水ref回指定金单() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("pf1"), eq(MqTopics.ORDER_PAID), eq(FINAL_NO)))
                .thenReturn(1);
        when(orderClient.getByOrderNo(FINAL_NO)).thenReturn(Result.success(orderWithItem(FINAL_NO)));
        when(stockLogMapper.selectByUk(FINAL_NO, SKU_ID, StockTypes.PRESALE.getCode())).thenReturn(null);
        when(stockLogMapper.selectPresaleDeposit(SKU_ID, FINAL_NO))
                .thenReturn(presaleLog(DEPOSIT_NO, StockLockStatuses.DEDUCTED.getCode()));
        when(skuMapper.selectById(SKU_ID)).thenReturn(presaleSku(100, 10, 10));

        stockService.handleOrderPaid(paidEvent("pf1", FINAL_NO, true));

        // occupied 不二次变动：不走任何 SKU 数量更新
        verify(skuMapper, never()).deductPresaleStock(anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(skuMapper, never()).confirmDeduct(anyLong(), org.mockito.ArgumentMatchers.anyInt());
        ArgumentCaptor<ProductStockLog> captor = ArgumentCaptor.forClass(ProductStockLog.class);
        verify(stockLogMapper).insert(captor.capture());
        ProductStockLog finalLog = captor.getValue();
        assertEquals(FINAL_NO, finalLog.getOrderNo());
        assertEquals(DEPOSIT_NO, finalLog.getRefOrderNo());
        assertEquals(StockLockStatuses.DEDUCTED.getCode(), finalLog.getStatus());
        assertEquals(StockTypes.PRESALE.getCode(), finalLog.getType());
    }

    @Test
    void 尾款违约取消_定金流水回补status5_重复取消幂等() {
        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .orderNo(DEPOSIT_NO).orderType(OrderTypes.PRESALE)
                .items(List.of(OrderItemMessage.builder().skuId(SKU_ID).qty(10).build()))
                .build();
        event.setEventId("px1");
        when(consumeRecordMapper.tryInsert(anyLong(), eq("px1"), eq(MqTopics.ORDER_CANCELLED), eq(DEPOSIT_NO)))
                .thenReturn(1);
        when(stockLogMapper.selectByUk(DEPOSIT_NO, SKU_ID, StockTypes.PRESALE.getCode()))
                .thenReturn(presaleLog(DEPOSIT_NO, 1))
                .thenReturn(presaleLog(DEPOSIT_NO, 5));
        when(skuMapper.returnPresaleStock(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.markPresaleRecover(666L)).thenReturn(1);

        stockService.handleOrderCancelled(event);
        verify(skuMapper).returnPresaleStock(SKU_ID, 10);
        verify(stockLogMapper).markPresaleRecover(666L);

        // 不同 eventId 重投：流水已 status=5，零库存动作
        event.setEventId("px2");
        when(consumeRecordMapper.tryInsert(anyLong(), eq("px2"), eq(MqTopics.ORDER_CANCELLED), eq(DEPOSIT_NO)))
                .thenReturn(1);
        stockService.handleOrderCancelled(event);
        verify(skuMapper, org.mockito.Mockito.times(1)).returnPresaleStock(SKU_ID, 10);
        verify(stockLogMapper, org.mockito.Mockito.times(1)).markPresaleRecover(666L);
    }

    @Test
    void 普通单MQ链路回归_下单锁_支付confirm_不走预售分支() {
        // 普通单 ORDER_CREATED 仍走 doLock
        when(consumeRecordMapper.tryInsert(anyLong(), eq("pn1"), eq(MqTopics.ORDER_CREATED), eq(ORDER_NO)))
                .thenReturn(1);
        when(stockLogMapper.selectByUk(ORDER_NO, SKU_ID, 1)).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(90, 10, 3));
        when(skuMapper.lockStock(SKU_ID, 10)).thenReturn(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(3));
        stockService.handleOrderCreated(orderCreatedEvent("pn1", OrderTypes.NORMAL));
        verify(skuMapper).lockStock(SKU_ID, 10);

        // 普通单 ORDER_PAID：不查订单域，按锁定流水 confirm
        PaymentSucceededEvent paid = PaymentSucceededEvent.builder().orderNo(ORDER_NO).build();
        paid.setEventId("pn2");
        when(consumeRecordMapper.tryInsert(anyLong(), eq("pn2"), eq(MqTopics.ORDER_PAID), eq(ORDER_NO)))
                .thenReturn(1);
        when(stockLogMapper.selectLockedByOrder(ORDER_NO)).thenReturn(List.of(log(0)));
        when(skuMapper.confirmDeduct(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.updateStatusIf(555L, 0, 1)).thenReturn(1);
        stockService.handleOrderPaid(paid);
        verify(skuMapper).confirmDeduct(SKU_ID, 10);
        verify(orderClient, never()).getByOrderNo(anyString());
    }

    @Test
    void 定金支付_预售池不足_抛STOCK_NOT_ENOUGH且事件可重试_不写流水() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("pe1"), eq(MqTopics.ORDER_PAID), eq(DEPOSIT_NO)))
                .thenReturn(1);
        when(orderClient.getByOrderNo(DEPOSIT_NO)).thenReturn(Result.success(orderWithItem(DEPOSIT_NO)));
        when(stockLogMapper.selectByUk(DEPOSIT_NO, SKU_ID, StockTypes.PRESALE.getCode())).thenReturn(null);
        when(skuMapper.selectById(SKU_ID)).thenReturn(presaleSku(100, 0, 5));
        when(skuMapper.deductPresaleStock(SKU_ID, 10)).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> stockService.handleOrderPaid(paidEvent("pe1", DEPOSIT_NO, null)));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), ex.getCode());
        verify(stockLogMapper, never()).insert(any());
    }

    // ==================================================================
    // B13：发货出账状态机 + 已出账退货回库
    // ==================================================================

    private OrderShippedEvent shippedEvent(String eventId, String orderNo, boolean withItems) {
        OrderShippedEvent event = OrderShippedEvent.builder()
                .orderNo(orderNo)
                .items(withItems
                        ? List.of(OrderItemMessage.builder().skuId(SKU_ID).qty(10).build())
                        : List.of())
                .build();
        event.setEventId(eventId);
        return event;
    }

    @Test
    void 发货_已扣减流水出账_1转4占用出账_重复发货零变化() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("s1"), eq(MqTopics.ORDER_SHIPPED), eq(ORDER_NO)))
                .thenReturn(1);
        when(stockLogMapper.selectByOrderAndSku(ORDER_NO, SKU_ID))
                .thenReturn(log(StockLockStatuses.DEDUCTED.getCode()))
                .thenReturn(log(StockLockStatuses.SHIPPED_ACCOUNTED.getCode()));
        when(skuMapper.shipOutStock(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.updateStatusIf(555L, 1, 4)).thenReturn(1);

        stockService.handleOrderShipped(shippedEvent("s1", ORDER_NO, true));
        verify(skuMapper).shipOutStock(SKU_ID, 10);
        verify(stockLogMapper).updateStatusIf(555L, 1, 4);

        // 重复发货消息：status=4 幂等，零库存动作
        when(consumeRecordMapper.tryInsert(anyLong(), eq("s2"), eq(MqTopics.ORDER_SHIPPED), eq(ORDER_NO)))
                .thenReturn(1);
        stockService.handleOrderShipped(shippedEvent("s2", ORDER_NO, true));
        verify(skuMapper, org.mockito.Mockito.times(1)).shipOutStock(SKU_ID, 10);
    }

    @Test
    void 发货_流水状态2或3_抛冲突可重试() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("s3"), eq(MqTopics.ORDER_SHIPPED), eq(ORDER_NO)))
                .thenReturn(1);
        when(stockLogMapper.selectByOrderAndSku(ORDER_NO, SKU_ID))
                .thenReturn(log(StockLockStatuses.RELEASED.getCode()));
        BizException ex1 = assertThrows(BizException.class,
                () -> stockService.handleOrderShipped(shippedEvent("s3", ORDER_NO, true)));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex1.getCode());

        when(consumeRecordMapper.tryInsert(anyLong(), eq("s4"), eq(MqTopics.ORDER_SHIPPED), eq(ORDER_NO)))
                .thenReturn(1);
        when(stockLogMapper.selectByOrderAndSku(ORDER_NO, SKU_ID))
                .thenReturn(log(StockLockStatuses.RETURNED.getCode()));
        BizException ex2 = assertThrows(BizException.class,
                () -> stockService.handleOrderShipped(shippedEvent("s4", ORDER_NO, true)));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex2.getCode());
        verify(skuMapper, never()).shipOutStock(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 已出账订单退货_买家责任回可售_商家责任入残次_均不再减occupied() {
        // 买家责任：status=4 → 3，只增 available
        when(stockLogMapper.selectDeductedByOrderAndSku(ORDER_NO, SKU_ID))
                .thenReturn(log(StockLockStatuses.SHIPPED_ACCOUNTED.getCode()));
        when(skuMapper.returnShippedToAvailable(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.markReturned(555L, 1)).thenReturn(1);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku(10, 10, 5));
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu(5));
        when(spuMapper.updateStatusIf(SPU_ID, 5, 3)).thenReturn(1);

        stockService.returnStock(StockReturnCommand.builder().orderNo(ORDER_NO).reason(1)
                .items(List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).build())).build());
        verify(skuMapper).returnShippedToAvailable(SKU_ID, 10);
        verify(skuMapper, never()).returnToAvailable(anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(skuMapper, never()).returnShippedToDefect(anyLong(), org.mockito.ArgumentMatchers.anyInt());

        // 商家责任（质量）：status=4 → 3，只增 defect
        org.mockito.Mockito.reset(skuMapper, stockLogMapper);
        when(stockLogMapper.selectDeductedByOrderAndSku(ORDER_NO, SKU_ID))
                .thenReturn(log(StockLockStatuses.SHIPPED_ACCOUNTED.getCode()));
        when(skuMapper.returnShippedToDefect(SKU_ID, 10)).thenReturn(1);
        when(stockLogMapper.markReturned(555L, 2)).thenReturn(1);
        stockService.returnStock(StockReturnCommand.builder().orderNo(ORDER_NO).reason(2)
                .items(List.of(StockItemCommand.builder().skuId(SKU_ID).qty(10).build())).build());
        verify(skuMapper).returnShippedToDefect(SKU_ID, 10);
        verify(skuMapper, never()).returnToDefect(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 发货事件找不到流水_抛冲突交Broker重试() {
        when(consumeRecordMapper.tryInsert(anyLong(), eq("s5"), eq(MqTopics.ORDER_SHIPPED), eq(ORDER_NO)))
                .thenReturn(1);
        when(stockLogMapper.selectByOrderAndSku(ORDER_NO, SKU_ID)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> stockService.handleOrderShipped(shippedEvent("s5", ORDER_NO, true)));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }
}
