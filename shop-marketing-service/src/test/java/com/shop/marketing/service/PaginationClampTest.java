package com.shop.marketing.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.coupon.mapper.CouponTargetMapper;
import com.shop.marketing.coupon.service.CouponAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 卡 API-M：分页参数经 PageQuery 收敛（pageSize 上限 200，pageNum<1 按 1）。 */
@ExtendWith(MockitoExtension.class)
class PaginationClampTest {

    @Mock
    private CouponMapper couponMapper;
    @Mock
    private CouponTargetMapper targetMapper;

    @Test
    void 超大pageSize夹到200_非法pageNum按1() {
        Page<Coupon> empty = new Page<>(1, 200);
        empty.setRecords(new ArrayList<>());
        empty.setTotal(0);

        CouponAdminService service = new CouponAdminService(couponMapper, targetMapper);
        ArgumentCaptor<Page<Coupon>> captor = ArgumentCaptor.forClass(Page.class);
        when(couponMapper.selectPage(captor.capture(), any())).thenReturn(empty);
        service.page(-1L, 999999L, null, null, null);

        Page<Coupon> used = captor.getValue();
        assertEquals(1, used.getCurrent());
        assertEquals(200, used.getSize());
    }
}
