package com.shop.order.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.api.order.dto.OrderDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.order.invoice.entity.OrderInvoice;
import com.shop.order.invoice.mapper.OrderInvoiceMapper;
import com.shop.order.order.dto.OrderPageQuery;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.mapper.OrderMapper;
import com.shop.order.support.OrderAssembler;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单查询单测（design 5.6）：归属鉴权（本人/商户/第三方）、空参校验、分页委托。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderQueryServiceImplTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderInvoiceMapper invoiceMapper;

    private OrderQueryServiceImpl service;

    private static final String NO = "260315011001000001";

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Order.class);
    }

    @BeforeEach
    void setUp() {
        service = new OrderQueryServiceImpl(orderMapper, orderItemMapper, invoiceMapper,
                new OrderAssembler());
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(orderItemMapper.selectList(any())).thenReturn(List.<OrderItem>of());
        when(invoiceMapper.selectOne(any())).thenReturn(null);
    }

    private Order order() {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo(NO);
        o.setUserId(1001L);
        o.setMerchantId(3001L);
        o.setStatus(10);
        return o;
    }

    @Test
    void detail_asOwner_allowed() {
        OrderDTO dto = service.detail(NO, 1001L, null);
        assertThat(dto.getOrderNo()).isEqualTo(NO);
    }

    @Test
    void detail_asMerchant_allowed() {
        OrderDTO dto = service.detail(NO, null, 3001L);
        assertThat(dto.getOrderNo()).isEqualTo(NO);
    }

    @Test
    void detail_thirdParty_forbidden() {
        assertThatThrownBy(() -> service.detail(NO, 2002L, 4002L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void detail_anonymous_forbidden() {
        assertThatThrownBy(() -> service.detail(NO, null, null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void detail_orderMissing_throwsNotFound() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.detail(NO, 1001L, null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND.getCode());
    }

    @Test
    void requireByOrderNo_blank_paramInvalid() {
        assertThatThrownBy(() -> service.requireByOrderNo("  "))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    void getByOrderNo_returnsDtoWithoutAuth() {
        assertThat(service.getByOrderNo(NO).getOrderNo()).isEqualTo(NO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageUser_delegatesToMapperAndWrapsResult() {
        Page<Order> page = new Page<>(1, 10);
        page.setRecords(List.of(order()));
        page.setTotal(1L);
        when(orderMapper.selectPage(any(Page.class), any())).thenReturn(page);

        PageResult<OrderDTO> result = service.pageUser(1001L, new OrderPageQuery());

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getOrderNo()).isEqualTo(NO);
        verify(orderMapper).selectPage(any(Page.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageMerchant_scopesByMerchantId() {
        Page<Order> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0L);
        when(orderMapper.selectPage(any(Page.class), any())).thenReturn(page);

        PageResult<OrderDTO> result = service.pageMerchant(3001L, new OrderPageQuery());

        assertThat(result.getTotal()).isZero();
        assertThat(result.getList()).isEmpty();
    }

    @Test
    void detail_loadsItemsAndInvoice() {
        OrderItem item = new OrderItem();
        item.setId(9L);
        item.setOrderNo(NO);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));
        OrderInvoice invoice = new OrderInvoice();
        invoice.setId(5L);
        invoice.setOrderNo(NO);
        when(invoiceMapper.selectOne(any())).thenReturn(invoice);

        OrderDTO dto = service.detail(NO, 1001L, null);

        assertThat(dto.getItems()).hasSize(1);
        assertThat(dto.getInvoice()).isNotNull();
    }

    // ---------------- C33 状态批量查询 ----------------

    @Test
    void listStatus_mapsExistingOrders_missingExcluded() {
        Order o1 = new Order();
        o1.setOrderNo("N1");
        o1.setStatus(10);
        o1.setOrderType(3);
        o1.setPresaleFinalStage(0);
        Order o2 = new Order();
        o2.setOrderNo("N2");
        o2.setStatus(20);
        o2.setOrderType(1);
        o2.setPresaleFinalStage(null);
        when(orderMapper.selectList(any())).thenReturn(List.of(o1, o2));

        var result = service.listStatus(List.of("N1", "N2", "N-MISSING"));

        assertThat(result).containsOnlyKeys("N1", "N2");
        assertThat(result.get("N1").getStatus()).isEqualTo(10);
        assertThat(result.get("N1").getOrderType()).isEqualTo(3);
        assertThat(result.get("N1").getPresaleFinalStage()).isFalse();
        assertThat(result.get("N2").getPresaleFinalStage()).isNull();
    }

    @Test
    void listStatus_empty_returnsEmptyMap() {
        assertThat(service.listStatus(List.of())).isEmpty();
    }

    @Test
    void listStatus_over100_throwsParamInvalid() {
        List<String> ids = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> "N" + i).toList();
        assertThatThrownBy(() -> service.listStatus(ids))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }
}
