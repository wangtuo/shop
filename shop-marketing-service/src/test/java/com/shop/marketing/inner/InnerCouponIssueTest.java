package com.shop.marketing.inner;

import com.shop.api.marketing.dto.CouponIssueCommand;
import com.shop.marketing.coupon.service.CouponService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H-1：发券仅可经内部 Feign 链路 /inner/marketing/coupon/issue 触发，
 * issueWay 缺省走系统补偿(4)，以 requestNo 幂等透传。
 */
@ExtendWith(MockitoExtension.class)
class InnerCouponIssueTest {

    @Mock
    private MarketingAppService marketingAppService;
    @Mock
    private CouponService couponService;

    @Test
    void 内部发券默认补偿方式并透传幂等号() {
        InnerMarketingController controller = new InnerMarketingController(marketingAppService, couponService);
        CouponIssueCommand cmd = CouponIssueCommand.builder()
                .userId(1001L).couponId(55L).issueWay(null).requestNo("REQ-1").build();
        when(couponService.issue(1001L, 55L, 4, "REQ-1")).thenReturn(9001L);
        assertEquals(9001L, controller.issueCoupon(cmd).getData());
        verify(couponService).issue(1001L, 55L, 4, "REQ-1");
    }

    @Test
    void 内部发券显式指定发放方式() {
        InnerMarketingController controller = new InnerMarketingController(marketingAppService, couponService);
        CouponIssueCommand cmd = CouponIssueCommand.builder()
                .userId(1001L).couponId(55L).issueWay(2).requestNo("REQ-2").build();
        when(couponService.issue(1001L, 55L, 2, "REQ-2")).thenReturn(9002L);
        assertEquals(9002L, controller.issueCoupon(cmd).getData());
    }
}
