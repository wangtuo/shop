package com.shop.marketing.service;

import com.shop.common.exception.BizException;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.service.ActivityAdminService;
import com.shop.marketing.activity.support.SeckillStockClient;
import com.shop.marketing.activity.mapper.SeckillSkuMapper;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.coupon.service.CouponAdminService;
import com.shop.marketing.coupon.mapper.CouponTargetMapper;
import com.shop.marketing.promo.entity.Promo;
import com.shop.marketing.promo.mapper.PromoLevelMapper;
import com.shop.marketing.promo.mapper.PromoMapper;
import com.shop.marketing.promo.mapper.PromoTargetMapper;
import com.shop.marketing.promo.service.PromoAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 卡 B2/API-M：三 AdminService.changeStatus 白名单 + 审核闸门（99/作废复活/未审核上架）。
 */
@ExtendWith(MockitoExtension.class)
class AdminChangeStatusGateTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private SeckillSkuMapper seckillSkuMapper;
    @Mock private SeckillStockClient stockClient;

    @Mock private PromoMapper promoMapper;
    @Mock private PromoLevelMapper levelMapper;
    @Mock private PromoTargetMapper promoTargetMapper;

    @Mock private CouponMapper couponMapper;
    @Mock private CouponTargetMapper couponTargetMapper;

    private ActivityAdminService activityService() {
        return new ActivityAdminService(activityMapper, seckillSkuMapper, stockClient);
    }

    private PromoAdminService promoService() {
        return new PromoAdminService(promoMapper, levelMapper, promoTargetMapper);
    }

    private CouponAdminService couponService() {
        return new CouponAdminService(couponMapper, couponTargetMapper);
    }

    @Test
    void 活动99状态参数非法() {
        Activity a = new Activity();
        a.setId(1L);
        a.setAuditStatus(2);
        a.setStatus(0);
        when(activityMapper.selectById(1L)).thenReturn(a);
        BizException ex = assertThrows(BizException.class, () -> activityService().changeStatus(1L, 99));
        assertEquals(10001, ex.getCode());
        verify(activityMapper, never()).updateStatus(anyLong(), anyInt(), anyInt());
    }

    @Test
    void 活动已结束不可复活() {
        Activity a = new Activity();
        a.setId(1L);
        a.setAuditStatus(2);
        a.setStatus(2);
        when(activityMapper.selectById(1L)).thenReturn(a);
        assertThrows(BizException.class, () -> activityService().changeStatus(1L, 1));
        verify(activityMapper, never()).updateStatus(anyLong(), anyInt(), anyInt());
    }

    @Test
    void 未通过审核活动禁止上架() {
        Activity a = new Activity();
        a.setId(1L);
        a.setAuditStatus(1);
        a.setStatus(0);
        when(activityMapper.selectById(1L)).thenReturn(a);
        BizException ex = assertThrows(BizException.class, () -> activityService().changeStatus(1L, 1));
        assertEquals(10005, ex.getCode());
        verify(activityMapper, never()).updateStatus(anyLong(), anyInt(), anyInt());
    }

    @Test
    void 已审核活动正常上架() {
        Activity a = new Activity();
        a.setId(1L);
        a.setAuditStatus(2);
        a.setStatus(0);
        when(activityMapper.selectById(1L)).thenReturn(a);
        when(activityMapper.updateStatus(1L, 0, 1)).thenReturn(1);
        activityService().changeStatus(1L, 1);
        verify(activityMapper).updateStatus(1L, 0, 1);
    }

    @Test
    void 促销99非法且未审核拒绝启用() {
        Promo p = new Promo();
        p.setId(2L);
        p.setAuditStatus(2);
        p.setStatus(0);
        when(promoMapper.selectById(2L)).thenReturn(p);
        assertThrows(BizException.class, () -> promoService().changeStatus(2L, 99));

        Promo p2 = new Promo();
        p2.setId(2L);
        p2.setAuditStatus(3);
        p2.setStatus(0);
        when(promoMapper.selectById(2L)).thenReturn(p2);
        BizException ex = assertThrows(BizException.class, () -> promoService().changeStatus(2L, 1));
        assertEquals(10005, ex.getCode());
    }

    @Test
    void 券作废不可复活() {
        Coupon c = new Coupon();
        c.setId(3L);
        c.setAuditStatus(2);
        c.setStatus(2);
        when(couponMapper.selectById(3L)).thenReturn(c);
        assertThrows(BizException.class, () -> couponService().changeStatus(3L, 1));
        verify(couponMapper, never()).updateStatus(anyLong(), anyInt(), anyInt());
    }

    @Test
    void 券草稿审核态禁止上架() {
        Coupon c = new Coupon();
        c.setId(3L);
        c.setAuditStatus(0);
        c.setStatus(0);
        when(couponMapper.selectById(3L)).thenReturn(c);
        assertThrows(BizException.class, () -> couponService().changeStatus(3L, 1));
        verify(couponMapper, never()).updateStatus(anyLong(), anyInt(), anyInt());
    }

    @Test
    void 已审核券0到1再作废合法() {
        Coupon c = new Coupon();
        c.setId(3L);
        c.setAuditStatus(2);
        c.setStatus(0);
        when(couponMapper.selectById(3L)).thenReturn(c);
        when(couponMapper.updateStatus(eq(3L), anyInt(), anyInt())).thenReturn(1);
        couponService().changeStatus(3L, 1);
        verify(couponMapper).updateStatus(3L, 0, 1);
    }
}
