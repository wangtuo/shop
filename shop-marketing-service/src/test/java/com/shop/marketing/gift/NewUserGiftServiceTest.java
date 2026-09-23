package com.shop.marketing.gift;

import com.shop.api.marketing.enums.CouponIssueWays;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.coupon.service.CouponService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 卡 B6：新人礼包发券服务（发券/幂等跳过/空模板/脏模板/异常重试）。 */
@ExtendWith(MockitoExtension.class)
class NewUserGiftServiceTest {

    @Mock
    private CouponMapper couponMapper;
    @Mock
    private CouponService couponService;
    @InjectMocks
    private NewUserGiftService service;

    private Coupon template(long id, int issueWay) {
        Coupon c = new Coupon();
        c.setId(id);
        c.setIssueWay(issueWay);
        c.setNewUserGift(1);
        c.setStatus(1);
        return c;
    }

    @Test
    void 每张新人券模板发一张且requestNo正确() {
        when(couponMapper.selectNewUserGiftTemplates())
                .thenReturn(List.of(template(11L, 3), template(22L, 3)));

        service.issueGift(1001L);

        ArgumentCaptor<String> no = ArgumentCaptor.forClass(String.class);
        verify(couponService, times(2)).issue(eq(1001L), anyLong(),
                eq(CouponIssueWays.NEW_USER.getCode()), no.capture());
        assertTrue(no.getAllValues().contains("NEWUSER:1001:11"));
        assertTrue(no.getAllValues().contains("NEWUSER:1001:22"));
    }

    @Test
    void 空模板跳过不发券() {
        when(couponMapper.selectNewUserGiftTemplates()).thenReturn(List.of());
        service.issueGift(1001L);
        verifyNoInteractions(couponService);

        when(couponMapper.selectNewUserGiftTemplates()).thenReturn(null);
        service.issueGift(1001L);
        verify(couponService, never()).issue(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    void 重复事件已持有限领错误跳过其余券继续发() {
        when(couponMapper.selectNewUserGiftTemplates())
                .thenReturn(List.of(template(11L, 3), template(22L, 3)));
        // relay 以不同 eventId 重发同一注册事件：第一张已持有（requestNo/countHeld 兜底）
        when(couponService.issue(1001L, 11L, CouponIssueWays.NEW_USER.getCode(), "NEWUSER:1001:11"))
                .thenThrow(new BizException(ErrorCode.COUPON_LIMIT, "新人礼包仅可领取一次"));

        service.issueGift(1001L);

        // 第二张仍正常发放
        verify(couponService).issue(1001L, 22L, CouponIssueWays.NEW_USER.getCode(), "NEWUSER:1001:22");
    }

    @Test
    void 模板下架或不在领取窗跳过() {
        when(couponMapper.selectNewUserGiftTemplates())
                .thenReturn(List.of(template(11L, 3)));
        when(couponService.issue(anyLong(), anyLong(), anyInt(), anyString()))
                .thenThrow(new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "优惠券不存在或已下架"));

        service.issueGift(1001L);
        verify(couponService, times(1)).issue(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    void 脏数据模板issueWay非3不发放() {
        when(couponMapper.selectNewUserGiftTemplates())
                .thenReturn(List.of(template(33L, 1)));
        service.issueGift(1001L);
        verify(couponService, never()).issue(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    void 未知异常向上抛出供框架重试() {
        when(couponMapper.selectNewUserGiftTemplates())
                .thenReturn(List.of(template(11L, 3)));
        when(couponService.issue(anyLong(), anyLong(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("DB down"));
        assertThrows(RuntimeException.class, () -> service.issueGift(1001L));
    }
}
