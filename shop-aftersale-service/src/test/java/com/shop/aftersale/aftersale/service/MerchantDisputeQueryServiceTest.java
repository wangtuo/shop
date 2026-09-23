package com.shop.aftersale.aftersale.service;

import com.shop.api.aftersale.dto.MerchantDisputeDTO;
import com.shop.aftersale.aftersale.mapper.AftersaleDisputeMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleOrderMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * FUNDS C8：商户未终结售后/介入单聚合查询——进行中/已完成/介入中三种数据形态。
 */
@ExtendWith(MockitoExtension.class)
class MerchantDisputeQueryServiceTest {

    @Mock private AftersaleOrderMapper orderMapper;
    @Mock private AftersaleDisputeMapper disputeMapper;
    @InjectMocks private MerchantDisputeQueryService service;

    private static final Long MERCHANT = 2002L;
    private static final LocalDateTime SINCE = LocalDateTime.now().minusDays(30);

    @Test
    void 存在进行中售后单_exists为true且计数为未终结数() {
        when(orderMapper.countOpenByMerchantSince(MERCHANT, SINCE)).thenReturn(2L);
        when(disputeMapper.countRecentIntervene(MERCHANT, SINCE)).thenReturn(0L);
        when(orderMapper.countFinishedByMerchantSince(MERCHANT, SINCE)).thenReturn(5L);

        MerchantDisputeDTO dto = service.existsOpenDispute(MERCHANT, SINCE);

        assertTrue(dto.getExists());
        assertEquals(2L, dto.getOpenCount());
        assertEquals(5L, dto.getRecentFinishedCount());
    }

    @Test
    void 存在介入中纠纷单_exists为true且并入openCount() {
        when(orderMapper.countOpenByMerchantSince(MERCHANT, SINCE)).thenReturn(1L);
        when(disputeMapper.countRecentIntervene(MERCHANT, SINCE)).thenReturn(1L);
        when(orderMapper.countFinishedByMerchantSince(MERCHANT, SINCE)).thenReturn(0L);

        MerchantDisputeDTO dto = service.existsOpenDispute(MERCHANT, SINCE);

        assertTrue(dto.getExists());
        // 未终结售后单 1 + 介入中纠纷单 1
        assertEquals(2L, dto.getOpenCount());
    }

    @Test
    void 全部已完成无介入_exists为false() {
        when(orderMapper.countOpenByMerchantSince(MERCHANT, SINCE)).thenReturn(0L);
        when(disputeMapper.countRecentIntervene(MERCHANT, SINCE)).thenReturn(0L);
        when(orderMapper.countFinishedByMerchantSince(MERCHANT, SINCE)).thenReturn(3L);

        MerchantDisputeDTO dto = service.existsOpenDispute(MERCHANT, SINCE);

        assertFalse(dto.getExists());
        assertEquals(0L, dto.getOpenCount());
        assertEquals(3L, dto.getRecentFinishedCount());
    }

    @Test
    void mapperSQL_终态口径钉死_50完成55拒绝90取消_纠纷30已裁决() throws Exception {
        Method open = AftersaleOrderMapper.class.getMethod(
                "countOpenByMerchantSince", Long.class, LocalDateTime.class);
        String openSql = String.join(" ", open.getAnnotation(Select.class).value());
        assertTrue(openSql.contains("status NOT IN (50, 55, 90)"), openSql);

        Method intervene = AftersaleDisputeMapper.class.getMethod(
                "countRecentIntervene", Long.class, LocalDateTime.class);
        String interveneSql = String.join(" ", intervene.getAnnotation(Select.class).value());
        assertTrue(interveneSql.contains("status <> 30"), interveneSql);
    }
}
