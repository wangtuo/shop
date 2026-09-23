package com.shop.marketing.activity.service;

import com.shop.api.marketing.dto.CalcItem;
import com.shop.common.exception.BizException;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.SeckillOrder;
import com.shop.marketing.activity.entity.SeckillSku;
import com.shop.marketing.activity.entity.SeckillUserBuy;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.SeckillOrderMapper;
import com.shop.marketing.activity.mapper.SeckillSkuMapper;
import com.shop.marketing.activity.mapper.SeckillUserBuyMapper;
import com.shop.marketing.activity.support.SeckillStockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀：多 key Lua 原子预占（P1-5）、DB/落单失败全量回补 Redis、扣减/释放幂等、
 * 回补键缺失按 DB 重建（P2-4）、事件同事务走 outbox（P1-1）。
 *
 * <p>R4-25：t_seckill_order 每 (orderNo,skuId) 一行；多 SKU 单逐行落库、逐 SKU 发键
 * （orderNo#sku{skuId}）；限购由 t_seckill_user_buy 计数行原子占件；确认/释放逐 SKU
 * 扣减与回补，释放件数回减限购计数。</p>
 */
@ExtendWith(MockitoExtension.class)
class SeckillServiceTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private SeckillSkuMapper seckillSkuMapper;
    @Mock private SeckillOrderMapper seckillOrderMapper;
    @Mock private SeckillUserBuyMapper seckillUserBuyMapper;
    @Mock private SeckillStockClient stockClient;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private IdGenerator idGenerator;

    private SeckillService service;

    @BeforeEach
    void setUp() {
        service = new SeckillService(activityMapper, seckillSkuMapper, seckillOrderMapper,
                seckillUserBuyMapper, stockClient, outboxPublisher, idGenerator);
    }

    private Activity activity() {
        Activity a = new Activity();
        a.setId(10L);
        a.setType(10);
        a.setStatus(1);
        a.setStartTime(LocalDateTime.now().minusMinutes(1));
        a.setEndTime(LocalDateTime.now().plusMinutes(10));
        // W4-4：本类验证 Lua/DB 机械动作，统一放开每用户件数上限，限购语义另见 SeckillServiceLimitTest
        a.setRuleJson("{\"perUserBuyLimit\":10}");
        return a;
    }

    private SeckillSku sku(long skuId, long pkId, int stock) {
        SeckillSku s = new SeckillSku();
        s.setId(pkId);
        s.setActivityId(10L);
        s.setSkuId(skuId);
        s.setTotalStock(stock);
        s.setLockedStock(0);
        s.setSoldStock(0);
        return s;
    }

    private List<CalcItem> items(long skuId, int qty) {
        return List.of(CalcItem.builder().skuId(skuId).qty(qty).salePriceFen(9900L).build());
    }

    private List<CalcItem> twoItems() {
        return List.of(
                CalcItem.builder().skuId(1L).qty(2).salePriceFen(9900L).build(),
                CalcItem.builder().skuId(2L).qty(1).salePriceFen(19900L).build());
    }

    @Test
    @DisplayName("锁定成功：Lua 批量预占 + DB 锁定 + 计数占件 + 落单 + outbox 登记 SKU 维度 LOCK 事件")
    void lock_库存充足_Redis与DB均锁定并登记outbox() {
        when(activityMapper.selectById(10L)).thenReturn(activity());
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10));
        when(stockClient.tryAcquireBatch(eq(10L), anyList(), anyList())).thenReturn(8L);
        when(seckillSkuMapper.lockStock(100L, 2)).thenReturn(1);
        when(seckillUserBuyMapper.claim(10L, 1L, 2, 10)).thenReturn(1);

        service.lock(1L, 10L, "O20260916001", items(1L, 2));

        verify(seckillSkuMapper).lockStock(100L, 2);
        verify(seckillUserBuyMapper).claim(10L, 1L, 2, 10);
        verify(seckillOrderMapper).insert(any(SeckillOrder.class));
        verify(outboxPublisher).publish(any(), eq("1"), any(), eq("O20260916001#sku1"));
    }

    @Test
    @DisplayName("R4-25 多 SKU 成功：一次批量 Lua、逐 SKU 锁 DB/落单行，LOCK 事件键各自带 SKU 后缀不撞 UK")
    void lock_多SKU_批量Lua一次预占_逐行落库逐键登记() {
        when(activityMapper.selectById(10L)).thenReturn(activity());
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10), sku(2L, 200L, 5));
        when(stockClient.tryAcquireBatch(eq(10L), eq(List.of(1L, 2L)), eq(List.of(2, 1)))).thenReturn(3L);
        when(seckillSkuMapper.lockStock(anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
        when(seckillUserBuyMapper.claim(10L, 1L, 3, 10)).thenReturn(1);

        service.lock(1L, 10L, "O1", twoItems());

        verify(stockClient, times(1)).tryAcquireBatch(eq(10L), eq(List.of(1L, 2L)), eq(List.of(2, 1)));
        verify(seckillSkuMapper).lockStock(100L, 2);
        verify(seckillSkuMapper).lockStock(200L, 1);
        verify(seckillOrderMapper, times(2)).insert(any(SeckillOrder.class));
        verify(outboxPublisher).publish(any(), eq("1"), any(), eq("O1#sku1"));
        verify(outboxPublisher).publish(any(), eq("1"), any(), eq("O1#sku2"));
    }

    @Test
    @DisplayName("P1-5 混合库存不足：Lua 整体回滚后不动 DB/订单/outbox/占件")
    void lock_Lua售罄_全部余量不变不落库() {
        when(activityMapper.selectById(10L)).thenReturn(activity());
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10), sku(2L, 200L, 5));
        when(stockClient.tryAcquireBatch(anyLong(), anyList(), anyList()))
                .thenReturn(SeckillStockClient.SOLD_OUT);

        assertThrows(BizException.class, () -> service.lock(1L, 10L, "O1", twoItems()));
        verify(seckillSkuMapper, never()).lockStock(anyLong(), anyInt());
        verify(seckillOrderMapper, never()).insert(any());
        verify(seckillUserBuyMapper, never()).claim(anyLong(), anyLong(), anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
        // 未预占成功，无需回补
        verify(stockClient, never()).release(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("Redis 批量未初始化：按 DB 可售逐 key 初始化后整批重试一次")
    void lock_批量未初始化_全量初始化后重试() {
        when(activityMapper.selectById(10L)).thenReturn(activity());
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10), sku(2L, 200L, 5));
        when(stockClient.tryAcquireBatch(anyLong(), anyList(), anyList()))
                .thenReturn(SeckillStockClient.NOT_INITIALIZED, 3L);
        when(seckillSkuMapper.lockStock(anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
        when(seckillUserBuyMapper.claim(10L, 1L, 3, 10)).thenReturn(1);

        service.lock(1L, 10L, "O1", twoItems());

        verify(stockClient).initStock(10L, 1L, 10);
        verify(stockClient).initStock(10L, 2L, 5);
        verify(stockClient, times(2)).tryAcquireBatch(eq(10L), eq(List.of(1L, 2L)), eq(List.of(2, 1)));
    }

    @Test
    @DisplayName("P1-5 第 N 个 SKU 的 DB 条件更新失败：前 N-1 个 Redis 预占逐笔回补后抛异常")
    void lock_DB中途不足_回补全部已扣Redis() {
        when(activityMapper.selectById(10L)).thenReturn(activity());
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10), sku(2L, 200L, 5));
        when(stockClient.tryAcquireBatch(anyLong(), anyList(), anyList())).thenReturn(3L);
        when(seckillSkuMapper.lockStock(100L, 2)).thenReturn(1);
        when(seckillSkuMapper.lockStock(200L, 1)).thenReturn(0);

        assertThrows(BizException.class, () -> service.lock(1L, 10L, "O1", twoItems()));
        verify(stockClient).release(10L, 1L, 2);
        verify(stockClient).release(10L, 2L, 1);
        verify(seckillOrderMapper, never()).insert(any());
        verify(seckillUserBuyMapper, never()).claim(anyLong(), anyLong(), anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("存在未配置的秒杀 SKU：触碰 Redis 前快速失败")
    void lock_SKU不存在_扣Redis前失败() {
        when(activityMapper.selectById(10L)).thenReturn(activity());
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10),
                (com.shop.marketing.activity.entity.SeckillSku) null);

        assertThrows(BizException.class, () -> service.lock(1L, 10L, "O1", twoItems()));
        verify(stockClient, never()).tryAcquireBatch(anyLong(), anyList(), anyList());
    }

    @Test
    @DisplayName("R4-25 占件计数超限（累计已达上限）：回补 Redis 并按超限购友好报错，不落单不发事件")
    void lock_计数占件超限_友好报错并回补() {
        when(activityMapper.selectById(10L)).thenReturn(activity());
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10));
        when(stockClient.tryAcquireBatch(anyLong(), anyList(), anyList())).thenReturn(8L);
        when(seckillSkuMapper.lockStock(anyLong(), anyInt())).thenReturn(1);
        // 计数行已存在，两次条件占件均失败
        when(seckillUserBuyMapper.claim(10L, 1L, 2, 10)).thenReturn(0);
        when(seckillUserBuyMapper.selectActive(10L, 1L)).thenReturn(new SeckillUserBuy());

        BizException ex = assertThrows(BizException.class,
                () -> service.lock(1L, 10L, "O-NEW", items(1L, 2)));
        verify(stockClient).release(10L, 1L, 2);
        verify(seckillOrderMapper, never()).insert(any());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
        assertTrue(ex.getMessage().contains("限购数量"));
    }

    @Test
    @DisplayName("R4-25 计数行不存在（首单）：插入计数行成功即占件成功")
    void lock_首单计数行不存在_插行占件成功() {
        when(activityMapper.selectById(10L)).thenReturn(activity());
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10));
        when(stockClient.tryAcquireBatch(anyLong(), anyList(), anyList())).thenReturn(8L);
        when(seckillSkuMapper.lockStock(anyLong(), anyInt())).thenReturn(1);
        when(seckillUserBuyMapper.claim(10L, 1L, 2, 10)).thenReturn(0);
        when(seckillUserBuyMapper.selectActive(10L, 1L)).thenReturn(null);
        when(idGenerator.nextId()).thenReturn(999L);

        service.lock(1L, 10L, "O1", items(1L, 2));

        verify(seckillUserBuyMapper).insert(any(SeckillUserBuy.class));
        verify(seckillOrderMapper).insert(any(SeckillOrder.class));
    }

    @Test
    @DisplayName("R4-25 并发首单计数行 UK 冲突：负方重试条件占件成功后正常落单")
    void lock_计数行并发冲突_重试条件占件成功() {
        when(activityMapper.selectById(10L)).thenReturn(activity());
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10));
        when(stockClient.tryAcquireBatch(anyLong(), anyList(), anyList())).thenReturn(8L);
        when(seckillSkuMapper.lockStock(anyLong(), anyInt())).thenReturn(1);
        when(seckillUserBuyMapper.claim(10L, 1L, 2, 10)).thenReturn(0, 1);
        when(seckillUserBuyMapper.selectActive(10L, 1L)).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_activity_user"))
                .when(seckillUserBuyMapper).insert(any(SeckillUserBuy.class));

        service.lock(1L, 10L, "O1", items(1L, 2));

        verify(seckillOrderMapper).insert(any(SeckillOrder.class));
    }

    @Test
    @DisplayName("R4-25 订单行 UK 冲突（同单同 SKU 重复提交）：回补 Redis 并按重复提交报错")
    void lock_订单行UK冲突_重复提交报错并回补() {
        when(activityMapper.selectById(10L)).thenReturn(activity());
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10));
        when(stockClient.tryAcquireBatch(anyLong(), anyList(), anyList())).thenReturn(8L);
        when(seckillSkuMapper.lockStock(anyLong(), anyInt())).thenReturn(1);
        when(seckillUserBuyMapper.claim(10L, 1L, 2, 10)).thenReturn(1);
        doThrow(new DuplicateKeyException("uk_order_sku")).when(seckillOrderMapper).insert(any());

        BizException ex = assertThrows(BizException.class,
                () -> service.lock(1L, 10L, "O1", items(1L, 2)));
        verify(stockClient).release(10L, 1L, 2);
        assertTrue(ex.getMessage().contains("重复提交"));
    }

    @Test
    @DisplayName("活动未开始：拒绝下单")
    void lock_活动未开始_拒绝() {
        Activity a = activity();
        a.setStartTime(LocalDateTime.now().plusHours(1));
        when(activityMapper.selectById(10L)).thenReturn(a);
        assertThrows(BizException.class, () -> service.lock(1L, 10L, "O1", items(1L, 2)));
    }

    @Test
    @DisplayName("支付扣减：0→1，DB 锁定转已售，outbox 登记 SKU 维度 DEDUCT 事件")
    void confirm_已锁定_转扣减() {
        when(seckillOrderMapper.selectListByOrderNo("O1")).thenReturn(List.of(order(1L, 2, 0)));
        when(seckillOrderMapper.updateStatus("O1", 0, 1)).thenReturn(1);
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10));
        when(seckillSkuMapper.deductStock(100L, 2)).thenReturn(1);

        service.confirm("O1");

        verify(seckillSkuMapper).deductStock(100L, 2);
        verify(outboxPublisher).publish(any(), eq("2"), any(), eq("O1#sku1"));
    }

    @Test
    @DisplayName("R4-25 多 SKU 支付扣减：全部行 0→1，逐 SKU 扣 DB 并发各自 DEDUCT 事件")
    void confirm_多SKU_逐行扣减() {
        when(seckillOrderMapper.selectListByOrderNo("O1"))
                .thenReturn(List.of(order(1L, 2, 0), order(2L, 1, 0)));
        when(seckillOrderMapper.updateStatus("O1", 0, 1)).thenReturn(2);
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 10), sku(2L, 200L, 5));

        service.confirm("O1");

        verify(seckillSkuMapper).deductStock(100L, 2);
        verify(seckillSkuMapper).deductStock(200L, 1);
        verify(outboxPublisher).publish(any(), eq("2"), any(), eq("O1#sku1"));
        verify(outboxPublisher).publish(any(), eq("2"), any(), eq("O1#sku2"));
    }

    @Test
    @DisplayName("重复扣减：已扣减状态直接幂等返回")
    void confirm_已扣减_幂等返回() {
        when(seckillOrderMapper.selectListByOrderNo("O1")).thenReturn(List.of(order(1L, 2, 1)));
        service.confirm("O1");
        verify(seckillSkuMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("取消释放：锁定回可售，Redis 回补，计数回减，outbox 登记 SKU 维度 RELEASE 事件")
    void release_已锁定_回补并通知() {
        when(seckillOrderMapper.selectListByOrderNo("O1")).thenReturn(List.of(order(1L, 2, 0)));
        when(seckillOrderMapper.updateStatus("O1", 0, 2)).thenReturn(1);
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 8));
        when(seckillSkuMapper.releaseStock(100L, 2)).thenReturn(1);
        when(stockClient.release(10L, 1L, 2)).thenReturn(6L);

        service.release("O1");

        verify(stockClient).release(10L, 1L, 2);
        verify(stockClient, never()).initStock(anyLong(), anyLong(), anyInt());
        verify(seckillUserBuyMapper).releaseQty(10L, 1L, 2);
        verify(outboxPublisher).publish(any(), eq("3"), any(), eq("O1#sku1"));
    }

    @Test
    @DisplayName("R4-25 多 SKU 取消释放：逐 SKU DB/Redis 回补，计数按总件数 3 回减，事件各自唯一")
    void release_多SKU_逐行回补_计数回减总件数() {
        when(seckillOrderMapper.selectListByOrderNo("O1"))
                .thenReturn(List.of(order(1L, 2, 0), order(2L, 1, 0)));
        when(seckillOrderMapper.updateStatus("O1", 0, 2)).thenReturn(2);
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku(1L, 100L, 8), sku(2L, 200L, 4));
        when(seckillSkuMapper.releaseStock(anyLong(), anyInt())).thenReturn(1);
        when(stockClient.release(anyLong(), anyLong(), anyInt())).thenReturn(3L);

        service.release("O1");

        verify(stockClient).release(10L, 1L, 2);
        verify(stockClient).release(10L, 2L, 1);
        verify(seckillUserBuyMapper).releaseQty(10L, 1L, 3);
        verify(outboxPublisher).publish(any(), eq("3"), any(), eq("O1#sku1"));
        verify(outboxPublisher).publish(any(), eq("3"), any(), eq("O1#sku2"));
    }

    @Test
    @DisplayName("P2-4 回补时 Redis 键缺失：不凭空 INCR，按 DB 可售重建并继续事件")
    void release_键缺失_按DB重建() {
        // 快照 total=10 locked=2 sold=0 → DB 释放前可售 8；reload 后按 DB 可售重建
        SeckillSku sk = sku(1L, 100L, 10);
        sk.setLockedStock(2);
        when(seckillOrderMapper.selectListByOrderNo("O1")).thenReturn(List.of(order(1L, 2, 0)));
        when(seckillOrderMapper.updateStatus("O1", 0, 2)).thenReturn(1);
        when(seckillSkuMapper.selectOne(any())).thenReturn(sk);
        when(seckillSkuMapper.releaseStock(100L, 2)).thenReturn(1);
        when(stockClient.release(10L, 1L, 2)).thenReturn(SeckillStockClient.RELEASE_KEY_MISSING);

        service.release("O1");

        // DB 释放后可售 = 10 - 2 - 0 = 8（mocked sku 快照），按此重建
        verify(stockClient).initStock(10L, 1L, 8);
        verify(outboxPublisher).publish(any(), eq("3"), any(), eq("O1#sku1"));
    }

    @Test
    @DisplayName("无锁定记录：释放按成功返回")
    void release_无记录_幂等返回() {
        when(seckillOrderMapper.selectListByOrderNo("O1")).thenReturn(List.of());
        service.release("O1");
        verify(stockClient, never()).release(anyLong(), anyLong(), anyInt());
    }

    private SeckillOrder order(long skuId, int qty, int status) {
        SeckillOrder order = new SeckillOrder();
        order.setActivityId(10L);
        order.setSkuId(skuId);
        order.setUserId(1L);
        order.setOrderNo("O1");
        order.setQty(qty);
        order.setStatus(status);
        return order;
    }
}
