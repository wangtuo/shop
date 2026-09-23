package com.shop.marketing.coupon.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H-1：对外 POST /coupons/issue 必须不存在（发券已收口为 /inner/marketing/coupon/issue）。
 */
class CouponIssueRemovedTest {

    @Test
    void c端发券入口不可达() {
        for (Method m : CouponCenterController.class.getDeclaredMethods()) {
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (post == null) {
                continue;
            }
            for (String path : post.value()) {
                assertFalse(path.contains("issue"), "C 端发券入口必须移除: " + path);
            }
        }
    }

    @Test
    void 领券与我的券等c端入口保留() {
        boolean claimKept = false;
        for (Method m : CouponCenterController.class.getDeclaredMethods()) {
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (post != null) {
                for (String path : post.value()) {
                    if ("/claim".equals(path)) {
                        claimKept = true;
                    }
                }
            }
        }
        assertTrue(claimKept, "正常领券入口应保留");
        assertTrue(CouponCenterController.class.isAnnotationPresent(RequestMapping.class));
    }
}
