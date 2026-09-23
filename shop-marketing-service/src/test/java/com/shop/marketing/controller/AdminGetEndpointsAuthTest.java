package com.shop.marketing.controller;

import com.shop.common.exception.BizException;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.marketing.activity.controller.ActivityAdminController;
import com.shop.marketing.activity.service.ActivityAdminService;
import com.shop.marketing.common.audit.AuditRejectRequest;
import com.shop.marketing.coupon.controller.CouponAdminController;
import com.shop.marketing.coupon.service.CouponAdminService;
import com.shop.marketing.promo.controller.PromoAdminController;
import com.shop.marketing.promo.service.PromoAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 卡 API-M A1：7 个漏鉴权 GET 全部仅平台 userType=2 可访问。
 * <ol>
 *   <li>GET /admin/activities（page）</li>
 *   <li>GET /admin/promos（page）</li>
 *   <li>GET /admin/promos/{id}（detail）</li>
 *   <li>GET /admin/promos/{id}/levels</li>
 *   <li>GET /admin/promos/{id}/targets</li>
 *   <li>GET /admin/coupons（page）</li>
 *   <li>GET /admin/coupons/{id}/targets</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AdminGetEndpointsAuthTest {

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

    @Test
    void 未登录访问7个GET全部拒绝() {
        // 1. activities page
        assertThrows(BizException.class,
                () -> activityAdminController.page(1, 20, null, null, null));
        // 2. promos page / 3. detail / 4. levels / 5. targets
        assertThrows(BizException.class,
                () -> promoAdminController.page(1, 20, null, null, null));
        assertThrows(BizException.class, () -> promoAdminController.detail(3L));
        assertThrows(BizException.class, () -> promoAdminController.levels(3L));
        assertThrows(BizException.class, () -> promoAdminController.targets(3L));
        // 6. coupons page / 7. targets
        assertThrows(BizException.class,
                () -> couponAdminController.page(1, 20, null, null, null));
        assertThrows(BizException.class, () -> couponAdminController.targets(2L));
        verifyNoInteractions(activityAdminService, couponAdminService, promoAdminService);
    }

    @Test
    void 普通用户访问7个GET全部403() {
        loginAs(0);
        BizException e1 = assertThrows(BizException.class,
                () -> activityAdminController.page(1, 20, null, null, null));
        BizException e2 = assertThrows(BizException.class, () -> promoAdminController.detail(3L));
        BizException e3 = assertThrows(BizException.class, () -> promoAdminController.levels(3L));
        BizException e4 = assertThrows(BizException.class, () -> promoAdminController.targets(3L));
        BizException e5 = assertThrows(BizException.class,
                () -> promoAdminController.page(1, 20, null, null, null));
        BizException e6 = assertThrows(BizException.class,
                () -> couponAdminController.page(1, 20, null, null, null));
        BizException e7 = assertThrows(BizException.class, () -> couponAdminController.targets(2L));
        for (BizException e : new BizException[]{e1, e2, e3, e4, e5, e6, e7}) {
            assertEquals(10003, e.getCode());
        }
        verifyNoInteractions(activityAdminService, couponAdminService, promoAdminService);
    }

    @Test
    void 商户访问7个GET全部403() {
        loginAs(1);
        assertThrows(BizException.class,
                () -> activityAdminController.page(1, 20, null, null, null));
        assertThrows(BizException.class, () -> promoAdminController.detail(3L));
        assertThrows(BizException.class, () -> promoAdminController.levels(3L));
        assertThrows(BizException.class, () -> promoAdminController.targets(3L));
        assertThrows(BizException.class, () -> promoAdminController.page(1, 20, null, null, null));
        assertThrows(BizException.class, () -> couponAdminController.page(1, 20, null, null, null));
        assertThrows(BizException.class, () -> couponAdminController.targets(2L));
        verifyNoInteractions(activityAdminService, couponAdminService, promoAdminService);
    }
}
