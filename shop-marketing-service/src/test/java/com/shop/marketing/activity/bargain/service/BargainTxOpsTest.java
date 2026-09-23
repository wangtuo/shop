package com.shop.marketing.activity.bargain.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.marketing.activity.bargain.entity.BargainHelp;
import com.shop.marketing.activity.bargain.mapper.BargainHelpMapper;
import com.shop.marketing.activity.entity.BargainRecord;
import com.shop.marketing.activity.mapper.BargainRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 砍价落库事务：uk 防重、乐观锁 CAS、CAS 与留痕同事务回滚。 */
@ExtendWith(MockitoExtension.class)
class BargainTxOpsTest {

    @Mock private BargainRecordMapper recordMapper;
    @Mock private BargainHelpMapper helpMapper;

    private BargainTxOps txOps;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BargainRecord.class);
        TableInfoHelper.initTableInfo(assistant, BargainHelp.class);
    }

    @BeforeEach
    void setUp() {
        txOps = new BargainTxOps(recordMapper, helpMapper);
    }

    private BargainRecord record() {
        BargainRecord r = new BargainRecord();
        r.setId(900L);
        r.setStatus(0);
        r.setVersion(3);
        r.setCurrentPriceFen(9000L);
        r.setFloorPriceFen(8000L);
        return r;
    }

    private BargainHelp help() {
        BargainHelp h = new BargainHelp();
        h.setRecordId(900L);
        h.setHelperUserId(2L);
        h.setCutFen(500L);
        return h;
    }

    @Test
    @DisplayName("同一帮砍人二次帮砍：拒绝且不写库")
    void duplicateHelperRejected() {
        when(helpMapper.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class,
                () -> txOps.applyCut(record(), help(), 8500L));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(recordMapper, never()).update(any(), any());
        verify(helpMapper, never()).insert(any());
    }

    @Test
    @DisplayName("乐观锁 CAS 失败（0 行）：拒绝且不插留痕")
    void casZeroRowsRejected() {
        when(helpMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.update(any(), any())).thenReturn(0);
        assertThrows(BizException.class, () -> txOps.applyCut(record(), help(), 8500L));
        verify(helpMapper, never()).insert(any());
    }

    @Test
    @DisplayName("正常帮砍：CAS 1 行后插入留痕")
    void success() {
        when(helpMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.update(any(), any())).thenReturn(1);
        txOps.applyCut(record(), help(), 8500L);
        ArgumentCaptor<BargainHelp> captor = ArgumentCaptor.forClass(BargainHelp.class);
        verify(helpMapper).insert(captor.capture());
        assertEquals(2L, captor.getValue().getHelperUserId());
        assertEquals(500L, captor.getValue().getCutFen());
    }

    @Test
    @DisplayName("uk 兜底：留痕唯一键冲突抛业务错（CAS 随事务回滚）")
    void insertDuplicateKeyRejected() {
        when(helpMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.update(any(), any())).thenReturn(1);
        when(helpMapper.insert(any())).thenThrow(new DuplicateKeyException("uk"));
        BizException ex = assertThrows(BizException.class,
                () -> txOps.applyCut(record(), help(), 8500L));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }
}
