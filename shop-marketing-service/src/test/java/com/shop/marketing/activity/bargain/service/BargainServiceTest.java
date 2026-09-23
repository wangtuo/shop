package com.shop.marketing.activity.bargain.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.SkuDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.common.util.JsonUtils;
import com.shop.marketing.activity.bargain.dto.BargainDetailVO;
import com.shop.marketing.activity.bargain.dto.HelpCutVO;
import com.shop.marketing.activity.bargain.entity.BargainHelp;
import com.shop.marketing.activity.bargain.mapper.BargainHelpMapper;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.BargainRecord;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.BargainRecordMapper;
import com.shop.marketing.activity.support.ActivityRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 砍价玩法：发起幂等、首刀/封顶/底价收敛、发起人/重复/过期拒绝、成交与过期条件更新。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BargainServiceTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private BargainRecordMapper recordMapper;
    @Mock private BargainHelpMapper helpMapper;
    @Mock private BargainTxOps txOps;
    @Mock private ProductClient productClient;
    @Mock private ObjectProvider<RedissonClient> redissonProvider;

    private BargainService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BargainRecord.class);
        TableInfoHelper.initTableInfo(assistant, BargainHelp.class);
    }

    @BeforeEach
    void setUp() {
        service = new BargainService(activityMapper, recordMapper, helpMapper,
                txOps, productClient, redissonProvider);
        // 单测统一降级：DB 乐观锁 + uk 兜底
        when(redissonProvider.getIfAvailable()).thenReturn(null);
    }

    private Activity activity(ActivityRule rule) {
        Activity a = new Activity();
        a.setId(11L);
        a.setType(13);
        a.setStatus(1);
        a.setStartTime(LocalDateTime.now().minusHours(1));
        a.setEndTime(LocalDateTime.now().plusHours(10));
        a.setRuleJson(JsonUtils.toJson(rule));
        return a;
    }

    private ActivityRule rule() {
        ActivityRule rule = new ActivityRule();
        rule.setOriginPriceFen(10000L);
        rule.setFloorPriceFen(8000L);
        rule.setBargainExpireHours(24);
        rule.setBargainSkuId(55L);
        rule.setBargainCutMinFen(100L);
        rule.setBargainCutMaxFen(300L);
        rule.setBargainHelpLimit(3);
        return rule;
    }

    private BargainRecord record(int status, long current, int helpCount) {
        BargainRecord r = new BargainRecord();
        r.setId(900L);
        r.setActivityId(11L);
        r.setSkuId(55L);
        r.setUserId(1L);
        r.setOriginPriceFen(10000L);
        r.setFloorPriceFen(8000L);
        r.setCurrentPriceFen(current);
        r.setHelpCount(helpCount);
        r.setStatus(status);
        r.setVersion(helpCount);
        r.setExpireTime(LocalDateTime.now().plusHours(5));
        return r;
    }

    @Test
    @DisplayName("发起：存在进行中记录幂等返回旧 ID")
    void startIdempotent() {
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectOne(any())).thenReturn(record(0, 9500, 1));
        assertEquals(900L, service.startBargain(1L, 11L));
        verify(recordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("发起：已成交/失效记录拒绝重复发起")
    void startConflictWhenFinished() {
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectOne(any())).thenReturn(record(1, 8000, 3));
        BizException ex = assertThrows(BizException.class, () -> service.startBargain(1L, 11L));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("发起：首次创建，价格/有效期/状态落库正确")
    void startCreate() {
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectOne(any())).thenReturn(null);
        when(recordMapper.insert(any())).thenAnswer(inv -> {
            ((BargainRecord) inv.getArgument(0)).setId(700L);
            return 1;
        });
        long id = service.startBargain(1L, 11L);
        assertEquals(700L, id);
        ArgumentCaptor<BargainRecord> captor = ArgumentCaptor.forClass(BargainRecord.class);
        verify(recordMapper).insert(captor.capture());
        BargainRecord saved = captor.getValue();
        assertEquals(10000L, saved.getCurrentPriceFen());
        assertEquals(8000L, saved.getFloorPriceFen());
        assertEquals(0, saved.getStatus());
        assertEquals(0, saved.getHelpCount());
        assertTrue(saved.getExpireTime().isAfter(LocalDateTime.now().plusHours(20)));
    }

    @Test
    @DisplayName("发起：规则无原价时取 SKU 现价")
    void startOriginFromSku() {
        ActivityRule r = rule();
        r.setOriginPriceFen(null);
        when(activityMapper.selectById(11L)).thenReturn(activity(r));
        when(recordMapper.selectOne(any())).thenReturn(null);
        SkuDTO sku = new SkuDTO();
        sku.setSkuId(55L);
        sku.setSalePriceFen(9900L);
        when(productClient.getSku(55L)).thenReturn(Result.success(sku));
        when(recordMapper.insert(any())).thenReturn(1);
        service.startBargain(1L, 11L);
        ArgumentCaptor<BargainRecord> captor = ArgumentCaptor.forClass(BargainRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(9900L, captor.getValue().getOriginPriceFen());
    }

    @Test
    @DisplayName("发起：底价非正或不低于原价拒绝")
    void startInvalidFloor() {
        ActivityRule bad = rule();
        bad.setFloorPriceFen(10000L);
        when(activityMapper.selectById(11L)).thenReturn(activity(bad));
        when(recordMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.startBargain(1L, 11L));
    }

    @Test
    @DisplayName("发起：uk 并发冲突后查到进行中记录则幂等返回")
    void startDuplicateKeyRace() {
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        when(recordMapper.selectOne(any())).thenReturn(null, record(0, 9000, 1));
        when(recordMapper.insert(any())).thenThrow(new DuplicateKeyException("uk"));
        assertEquals(900L, service.startBargain(1L, 11L));
    }

    @Test
    @DisplayName("活动不存在/非进行中：发起拒绝")
    void startActivityUnavailable() {
        when(activityMapper.selectById(11L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.startBargain(1L, 11L));
        assertEquals(ErrorCode.ACTIVITY_NOT_AVAILABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("帮砍：发起人不能给自己砍")
    void helpSelfRejected() {
        BargainRecord r = record(0, 9500, 1);
        when(recordMapper.selectById(900L)).thenReturn(r);
        BizException ex = assertThrows(BizException.class, () -> service.helpCut(1L, 900L));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(txOps, never()).applyCut(any(), any(), anyLong());
    }

    @Test
    @DisplayName("帮砍：记录不存在 404")
    void helpNotFound() {
        when(recordMapper.selectById(900L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.helpCut(2L, 900L));
    }

    @Test
    @DisplayName("首刀：砍额落在规则区间，砍后价正确，未达底价")
    void helpFirstCut() {
        BargainRecord r = record(0, 10000, 0);
        when(recordMapper.selectById(900L)).thenReturn(r);
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));

        HelpCutVO vo = service.helpCut(2L, 900L);

        long expectCut = BargainService.deterministicCut(900L, 0, 100L, 300L);
        assertEquals(expectCut, vo.getCutFen());
        assertEquals(10000L - expectCut, vo.getCurrentPriceFen());
        assertEquals(1, vo.getHelpCount());
        assertFalse(vo.getReachedFloor());

        ArgumentCaptor<BargainHelp> helpCap = ArgumentCaptor.forClass(BargainHelp.class);
        verify(txOps).applyCut(any(), helpCap.capture(), org.mockito.ArgumentMatchers.eq(10000L - expectCut));
        assertEquals(2L, helpCap.getValue().getHelperUserId());
        assertEquals(expectCut, helpCap.getValue().getCutFen());
    }

    @Test
    @DisplayName("底价收敛：剩余可砍小于最小刀时一刀砍到底价，不破 floor")
    void helpClampToFloor() {
        BargainRecord r = record(0, 8050, 2);
        when(recordMapper.selectById(900L)).thenReturn(r);
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));

        HelpCutVO vo = service.helpCut(3L, 900L);

        assertEquals(50L, vo.getCutFen());
        assertEquals(8000L, vo.getCurrentPriceFen());
        assertTrue(vo.getReachedFloor());
    }

    @Test
    @DisplayName("已到底价再砍拒绝")
    void helpAtFloorRejected() {
        BargainRecord r = record(0, 8000, 2);
        when(recordMapper.selectById(900L)).thenReturn(r);
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        BizException ex = assertThrows(BizException.class, () -> service.helpCut(2L, 900L));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("帮砍人数封顶后拒绝")
    void helpLimitReached() {
        BargainRecord r = record(0, 9000, 3);
        when(recordMapper.selectById(900L)).thenReturn(r);
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        BizException ex = assertThrows(BizException.class, () -> service.helpCut(2L, 900L));
        assertEquals(ErrorCode.LIMIT_PURCHASE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("过期/非砍价中记录拒绝帮砍")
    void helpExpiredRejected() {
        BargainRecord r = record(2, 9000, 1);
        when(recordMapper.selectById(900L)).thenReturn(r);
        BizException ex = assertThrows(BizException.class, () -> service.helpCut(2L, 900L));
        assertEquals(ErrorCode.ACTIVITY_NOT_AVAILABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("确定性砍额：任何刀次均落在闭区间 [min,max]，min=max 恒定，首刀值可钉死")
    void deterministicCutBounds() {
        for (int i = 0; i < 5000; i++) {
            long cut = BargainService.deterministicCut(123456789L, i, 100L, 300L);
            assertTrue(cut >= 100L && cut <= 300L, "cut out of range: " + cut);
        }
        assertEquals(100L, BargainService.deterministicCut(123456789L, 0, 100L, 100L));
        // 确定性：同参重算一致
        long first = BargainService.deterministicCut(900L, 0, 100L, 300L);
        assertEquals(first, BargainService.deterministicCut(900L, 0, 100L, 300L));
        // 钉死首刀（SplitMix64 算法改动需同步评审）
        assertEquals(237L, first);
    }

    @Test
    @DisplayName("成交预占：CAS 成功/失败返回布尔")
    void markDealt() {
        when(recordMapper.update(any(), any())).thenReturn(1, 0);
        assertTrue(service.markDealt("O1", 1L, 11L));
        assertFalse(service.markDealt("O2", 1L, 11L));
    }

    @Test
    @DisplayName("过期扫描返回过期条数")
    void expireScan() {
        when(recordMapper.update(any(), any())).thenReturn(7);
        assertEquals(7, service.expireScan());
    }

    @Test
    @DisplayName("详情：组装价格/剩余秒数/帮砍列表")
    void detail() {
        BargainRecord r = record(0, 8500, 1);
        when(recordMapper.selectById(900L)).thenReturn(r);
        when(activityMapper.selectById(11L)).thenReturn(activity(rule()));
        BargainHelp h = new BargainHelp();
        h.setHelperUserId(2L);
        h.setCutFen(500L);
        when(helpMapper.selectList(any())).thenReturn(List.of(h));

        BargainDetailVO vo = service.detail(900L);
        assertEquals(8500L, vo.getCurrentPriceFen());
        assertEquals(3, vo.getHelpLimit());
        assertFalse(vo.getReachedFloor());
        assertTrue(vo.getRemainSeconds() > 0);
        assertEquals(1, vo.getHelps().size());
        assertEquals(2L, vo.getHelps().get(0).getHelperUserId());
    }
}
