package com.shop.marketing.controller;

import com.shop.common.exception.BizException;
import com.shop.framework.audit.AuditLogAspect;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.marketing.activity.controller.ActivityAdminController;
import com.shop.marketing.activity.dto.ActivitySaveRequest;
import com.shop.marketing.activity.service.ActivityAdminService;
import com.shop.marketing.common.audit.AuditRejectRequest;
import com.shop.marketing.common.audit.MarketingAuditService;
import com.shop.marketing.coupon.controller.CouponAdminController;
import com.shop.marketing.coupon.dto.CouponSaveRequest;
import com.shop.marketing.coupon.service.CouponAdminService;
import com.shop.marketing.promo.controller.PromoAdminController;
import com.shop.marketing.promo.dto.PromoSaveRequest;
import com.shop.marketing.promo.service.PromoAdminService;
import com.shop.marketing.support.MarketingStatusMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * H-1：/admin/activities、/admin/coupons、/admin/promos 的所有写操作仅平台 userType=2 可用，
 * 消费者/商户一律 403 且不得触达 service。
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerAuthTest {

    @Mock
    private ActivityAdminService activityAdminService;
    @Mock
    private CouponAdminService couponAdminService;
    @Mock
    private PromoAdminService promoAdminService;

    private ActivityAdminController activityAdminController;
    private CouponAdminController couponAdminController;
    private PromoAdminController promoAdminController;

    @BeforeEach
    void setUp() {
        activityAdminController = new ActivityAdminController(activityAdminService);
        couponAdminController = new CouponAdminController(couponAdminService);
        promoAdminController = new PromoAdminController(promoAdminService);
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private void loginAs(Integer userType) {
        UserContext.set(LoginUser.builder().userId(1001L).userType(userType).merchantId(2002L).build());
    }

    // ---------- 活动 ----------

    @Test
    void 普通用户新建活动被403拒绝() {
        loginAs(0);
        BizException ex = assertThrows(BizException.class,
                () -> activityAdminController.save(new ActivitySaveRequest()));
        assertEquals(10003, ex.getCode());
        verifyNoInteractions(activityAdminService);
    }

    @Test
    void 商户上下架活动被403拒绝() {
        loginAs(1);
        BizException ex = assertThrows(BizException.class,
                () -> activityAdminController.changeStatus(1L, 1));
        assertEquals(10003, ex.getCode());
        verifyNoInteractions(activityAdminService);
    }

    @Test
    void 平台运营新建与上下架活动通过() {
        loginAs(2);
        when(activityAdminService.save(org.mockito.ArgumentMatchers.any())).thenReturn(77L);
        assertEquals(77L, activityAdminController.save(new ActivitySaveRequest()).getData());
        activityAdminController.changeStatus(1L, 1);
        verify(activityAdminService).changeStatus(1L, 1);
    }

    // ---------- 优惠券 ----------

    @Test
    void 普通用户新建优惠券被403拒绝() {
        loginAs(0);
        BizException ex = assertThrows(BizException.class,
                () -> couponAdminController.save(new CouponSaveRequest()));
        assertEquals(10003, ex.getCode());
        verifyNoInteractions(couponAdminService);
    }

    @Test
    void 商户上下架优惠券被403拒绝() {
        loginAs(1);
        BizException ex = assertThrows(BizException.class,
                () -> couponAdminController.changeStatus(2L, 0));
        assertEquals(10003, ex.getCode());
        verifyNoInteractions(couponAdminService);
    }

    @Test
    void 平台运营新建与上下架优惠券通过() {
        loginAs(2);
        when(couponAdminService.save(org.mockito.ArgumentMatchers.any())).thenReturn(88L);
        assertEquals(88L, couponAdminController.save(new CouponSaveRequest()).getData());
        couponAdminController.changeStatus(2L, 0);
        verify(couponAdminService).changeStatus(2L, 0);
    }

    // ---------- 促销规则 ----------

    @Test
    void 普通用户新建促销被403拒绝() {
        loginAs(0);
        BizException ex = assertThrows(BizException.class,
                () -> promoAdminController.save(new PromoSaveRequest()));
        assertEquals(10003, ex.getCode());
        verifyNoInteractions(promoAdminService);
    }

    @Test
    void 商户上下架促销被403拒绝() {
        loginAs(1);
        BizException ex = assertThrows(BizException.class,
                () -> promoAdminController.changeStatus(3L, 1));
        assertEquals(10003, ex.getCode());
        verifyNoInteractions(promoAdminService);
    }

    @Test
    void 平台运营新建与上下架促销通过() {
        loginAs(2);
        when(promoAdminService.save(org.mockito.ArgumentMatchers.any())).thenReturn(99L);
        assertEquals(99L, promoAdminController.save(new PromoSaveRequest()).getData());
        promoAdminController.changeStatus(3L, 1);
        verify(promoAdminService).changeStatus(3L, 1);
    }

    // ---------- O5 审计切面代理链 ----------

    /** O5：AuditLogAspect 代理包装后写调用不被破坏，返回值透传、service 正常触达。 */
    @Test
    void 审计切面代理链不破坏admin写调用() {
        loginAs(2);

        ActivityAdminController activityProxy = auditedProxy(activityAdminController);
        when(activityAdminService.save(org.mockito.ArgumentMatchers.any())).thenReturn(77L);
        assertEquals(77L, activityProxy.save(new ActivitySaveRequest()).getData());
        activityProxy.changeStatus(1L, 1);
        verify(activityAdminService).changeStatus(1L, 1);

        CouponAdminController couponProxy = auditedProxy(couponAdminController);
        when(couponAdminService.save(org.mockito.ArgumentMatchers.any())).thenReturn(88L);
        assertEquals(88L, couponProxy.save(new CouponSaveRequest()).getData());
        couponProxy.changeStatus(2L, 0);
        verify(couponAdminService).changeStatus(2L, 0);

        PromoAdminController promoProxy = auditedProxy(promoAdminController);
        when(promoAdminService.save(org.mockito.ArgumentMatchers.any())).thenReturn(99L);
        assertEquals(99L, promoProxy.save(new PromoSaveRequest()).getData());
        promoProxy.changeStatus(3L, 1);
        verify(promoAdminService).changeStatus(3L, 1);

        // 平台审核台 approve/reject 同样走代理链且 domain 解析、操作人透传不被破坏
        MarketingAuditService marketingAuditService = org.mockito.Mockito.mock(MarketingAuditService.class);
        PlatformAuditController auditProxy = auditedProxy(new PlatformAuditController(marketingAuditService));
        auditProxy.approve("promo", 42L);
        verify(marketingAuditService).approve(MarketingStatusMachine.Domain.PROMO, 42L, 1001L);
        AuditRejectRequest rejectReq = new AuditRejectRequest();
        rejectReq.setRemark("不合规");
        auditProxy.reject("coupon", 43L, rejectReq);
        verify(marketingAuditService).reject(MarketingStatusMachine.Domain.COUPON, 43L, 1001L, "不合规");
    }

    private static <T> T auditedProxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new AuditLogAspect());
        @SuppressWarnings("unchecked")
        T proxy = (T) factory.getProxy();
        return proxy;
    }
}
