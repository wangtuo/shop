package com.shop.marketing.activity.service;

import com.shop.api.marketing.dto.CalcItem;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.SeckillSku;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4-4/B4 C24 限购可配置：perUserBuyLimit（rule_json，缺省 1）。
 *
 * <p>R4-25：强约束从 t_seckill_order 的 uk_activity_user（硬 1 单，与 limit&gt;1 矛盾）
 * 改为 t_seckill_user_buy 计数行的行锁条件占件（total_qty + qty &lt;= limit），
 * 支持同用户同场次多单累计、并发首单建行互斥；取消释放时件数回减（见 SeckillServiceTest）。</p>
 */
@ExtendWith(MockitoExtension.class)
class SeckillServiceLimitTest {

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

    private Activity activity(String ruleJson) {
        Activity a = new Activity();
        a.setId(10L);
        a.setType(10);
        a.setStatus(1);
        a.setStartTime(LocalDateTime.now().minusMinutes(1));
        a.setEndTime(LocalDateTime.now().plusMinutes(10));
        a.setRuleJson(ruleJson);
        return a;
    }

    private SeckillSku sku() {
        SeckillSku s = new SeckillSku();
        s.setId(100L);
        s.setActivityId(10L);
        s.setSkuId(1L);
        s.setTotalStock(100);
        s.setLockedStock(0);
        s.setSoldStock(0);
        return s;
    }

    private List<CalcItem> items(int qty) {
        return List.of(CalcItem.builder().skuId(1L).qty(qty).salePriceFen(9900L).build());
    }

    private void stubStockReady() {
        when(seckillSkuMapper.selectOne(any())).thenReturn(sku());
        when(stockClient.tryAcquireBatch(anyLong(), anyList(), anyList())).thenReturn(90L);
        when(seckillSkuMapper.lockStock(anyLong(), anyInt())).thenReturn(1);
    }

    @Test
    @DisplayName("缺省（rule_json 无 perUserBuyLimit）：1 件成功，2 件在触碰 Redis 前被拒——默认值 1 不回退")
    void limit_缺省默认1_一件成功两件拒绝() {
        when(activityMapper.selectById(10L)).thenReturn(activity(null));

        // 首件：计数行不存在 → 插入成功
        stubStockReady();
        when(seckillUserBuyMapper.claim(10L, 1L, 1, 1)).thenReturn(0);
        when(seckillUserBuyMapper.selectActive(10L, 1L)).thenReturn(null);
        when(idGenerator.nextId()).thenReturn(1L);
        service.lock(1L, 10L, "O1", items(1));
        verify(seckillOrderMapper).insert(any());

        // 再来 2 件：单笔即超默认上限 1，触碰 Redis 前拒绝
        org.mockito.Mockito.reset(stockClient, seckillSkuMapper, seckillOrderMapper);
        BizException ex = assertThrows(BizException.class,
                () -> service.lock(1L, 10L, "O2", items(2)));
        assertEquals(ErrorCode.LIMIT_PURCHASE.getCode(), ex.getCode());
        verify(stockClient, never()).tryAcquireBatch(anyLong(), anyList(), anyList());
        verify(seckillSkuMapper, never()).lockStock(anyLong(), anyInt());
        verify(seckillOrderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("perUserBuyLimit=3：单笔 1 件连续 3 单成功，第 4 件计数占件失败拒绝")
    void limit_配置3_连续单边界() {
        when(activityMapper.selectById(10L)).thenReturn(activity("{\"perUserBuyLimit\":3}"));
        stubStockReady();
        // 第 1 单建行（claim 0→插行），第 2、3 单条件占件成功，第 4 单两次占件均失败
        when(seckillUserBuyMapper.claim(10L, 1L, 1, 3)).thenReturn(0, 1, 1, 0, 0);
        when(seckillUserBuyMapper.selectActive(10L, 1L))
                .thenReturn(null); // 仅首单需要
        when(idGenerator.nextId()).thenReturn(1L);

        for (int i = 0; i < 3; i++) {
            service.lock(1L, 10L, "O" + i, items(1));
        }
        verify(seckillOrderMapper, org.mockito.Mockito.times(3)).insert(any());
        org.mockito.Mockito.clearInvocations(seckillOrderMapper);
        // 第 4 件：Redis/DB 已动作后计数占件落败 → 回补后友好报错
        com.shop.marketing.activity.entity.SeckillUserBuy row =
                new com.shop.marketing.activity.entity.SeckillUserBuy();
        when(seckillUserBuyMapper.selectActive(10L, 1L)).thenReturn(row);
        BizException ex = assertThrows(BizException.class,
                () -> service.lock(1L, 10L, "O4", items(1)));
        assertEquals(ErrorCode.LIMIT_PURCHASE.getCode(), ex.getCode());
        verify(stockClient).release(10L, 1L, 1);
        verify(seckillOrderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("perUserBuyLimit=3：单笔 3 件恰好成功，单笔 4 件触碰 Redis 前拒绝")
    void limit_配置3_单笔数量边界() {
        when(activityMapper.selectById(10L)).thenReturn(activity("{\"perUserBuyLimit\":3}"));
        // 单笔 3 件成功
        stubStockReady();
        when(seckillUserBuyMapper.claim(10L, 1L, 3, 3)).thenReturn(1);
        service.lock(1L, 10L, "O3", items(3));
        verify(seckillOrderMapper).insert(any());

        // 单笔 4 件：前置闸门
        assertThrows(BizException.class, () -> service.lock(1L, 10L, "O4", items(4)));
    }

    @Test
    @DisplayName("累计口径：已占 1 件后单笔 3 件（1+3>3）计数占件落败回补拒绝；1+2=3 占件成功")
    void limit_累计占件口径() {
        when(activityMapper.selectById(10L)).thenReturn(activity("{\"perUserBuyLimit\":3}"));
        stubStockReady();
        com.shop.marketing.activity.entity.SeckillUserBuy row =
                new com.shop.marketing.activity.entity.SeckillUserBuy();
        // OBIG：首次占件 0 落败 → 计数行已存在 → 二次占件仍 0 落败（1+3>3）→ 回补拒绝
        // O2：占 2 件成功（1+2=3）
        when(seckillUserBuyMapper.claim(10L, 1L, 3, 3)).thenReturn(0, 0);
        when(seckillUserBuyMapper.claim(10L, 1L, 2, 3)).thenReturn(1);
        when(seckillUserBuyMapper.selectActive(10L, 1L)).thenReturn(row);
        assertThrows(BizException.class, () -> service.lock(1L, 10L, "OBIG", items(3)));

        // 第二次：2 件占件成功
        service.lock(1L, 10L, "O2", items(2));
        verify(seckillOrderMapper).insert(any());
    }

    @Test
    @DisplayName("rule_json 非法或缺字段：回退默认 1，2 件在触碰 Redis 前拒绝（存量活动兼容）")
    void limit_脏ruleJson_回退默认1() {
        when(activityMapper.selectById(10L)).thenReturn(activity("not-a-json"));
        BizException ex = assertThrows(BizException.class,
                () -> service.lock(1L, 10L, "O2", items(2)));
        assertEquals(ErrorCode.LIMIT_PURCHASE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("下单数量 0/非法：PARAM_INVALID")
    void limit_数量非法() {
        when(activityMapper.selectById(10L)).thenReturn(activity(null));
        assertThrows(BizException.class, () -> service.lock(1L, 10L, "O0", items(0)));
    }
}
