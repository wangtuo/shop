package com.shop.marketing.common.audit;

import com.shop.common.exception.BizException;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.promo.entity.Promo;
import com.shop.marketing.promo.mapper.PromoMapper;
import com.shop.marketing.support.MarketingStatusMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 卡 B2：提交/通过/驳回服务流（归属、状态、remark、CAS 0 行冲突）。 */
@ExtendWith(MockitoExtension.class)
class MarketingAuditServiceTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private PromoMapper promoMapper;
    @Mock private CouponMapper couponMapper;
    @InjectMocks private MarketingAuditService service;

    @Test
    void 商户提交活动成功() {
        Activity a = new Activity();
        a.setId(1L);
        a.setMerchantId(2002L);
        a.setAuditStatus(0);
        when(activityMapper.selectById(1L)).thenReturn(a);
        when(activityMapper.casSubmitAudit(eq(1L), any())).thenReturn(1);

        service.submitActivity(1L, 2002L);
        verify(activityMapper).casSubmitAudit(eq(1L), any());
    }

    @Test
    void 非所属商户提交403() {
        Activity a = new Activity();
        a.setId(1L);
        a.setMerchantId(2002L);
        a.setAuditStatus(0);
        when(activityMapper.selectById(1L)).thenReturn(a);

        BizException ex = assertThrows(BizException.class, () -> service.submitActivity(1L, 9999L));
        assertEquals(10003, ex.getCode());
        verify(activityMapper, never()).casSubmitAudit(any(), any());
    }

    @Test
    void 待审核态不可重复提交() {
        Promo p = new Promo();
        p.setId(2L);
        p.setMerchantId(2002L);
        p.setAuditStatus(1);
        when(promoMapper.selectById(2L)).thenReturn(p);

        assertThrows(BizException.class, () -> service.submitPromo(2L, 2002L));
        verify(promoMapper, never()).casSubmitAudit(any(), any());
    }

    @Test
    void 单据不存在提交报404() {
        when(couponMapper.selectById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.submitCoupon(9L, 2002L));
        assertEquals(10004, ex.getCode());
    }

    @Test
    void 平台通过审批() {
        Coupon c = new Coupon();
        c.setId(3L);
        c.setAuditStatus(1);
        when(couponMapper.selectById(3L)).thenReturn(c);
        when(couponMapper.casAudit(eq(3L), eq(2), eq(77L), any(), any())).thenReturn(1);

        service.approve(MarketingStatusMachine.Domain.COUPON, 3L, 77L);
        verify(couponMapper).casAudit(eq(3L), eq(2), eq(77L), any(), any());
    }

    @Test
    void 平台驳回必须带原因() {
        assertThrows(BizException.class,
                () -> service.reject(MarketingStatusMachine.Domain.PROMO, 2L, 77L, "  "));
        assertThrows(BizException.class,
                () -> service.reject(MarketingStatusMachine.Domain.ACTIVITY, 1L, 77L, null));
        verify(promoMapper, never()).casAudit(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    @Test
    void 平台驳回成功写remark() {
        Promo p = new Promo();
        p.setId(2L);
        p.setAuditStatus(1);
        when(promoMapper.selectById(2L)).thenReturn(p);
        when(promoMapper.casAudit(eq(2L), eq(3), eq(77L), any(), eq("门槛设置有误"))).thenReturn(1);

        service.reject(MarketingStatusMachine.Domain.PROMO, 2L, 77L, "门槛设置有误");
    }

    @Test
    void CAS零行视为冲突() {
        Activity a = new Activity();
        a.setId(1L);
        a.setAuditStatus(2); // 已被他人审批
        when(activityMapper.selectById(1L)).thenReturn(a);
        when(activityMapper.casAudit(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any())).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.approve(MarketingStatusMachine.Domain.ACTIVITY, 1L, 77L));
    }

    @Test
    void 驳回后可再次提交() {
        Activity a = new Activity();
        a.setId(1L);
        a.setMerchantId(2002L);
        a.setAuditStatus(3);
        when(activityMapper.selectById(1L)).thenReturn(a);
        when(activityMapper.casSubmitAudit(eq(1L), any())).thenReturn(1);

        service.submitActivity(1L, 2002L);
        verify(activityMapper).casSubmitAudit(eq(1L), any());
    }
}
