package com.shop.order.invoice.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.enums.OrderStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.order.invoice.entity.OrderInvoice;
import com.shop.order.invoice.mapper.OrderInvoiceMapper;
import com.shop.order.order.dto.InvoiceRequest;
import com.shop.order.order.entity.Order;
import com.shop.order.order.service.OrderQueryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发票服务单测（design 5.5）：登记校验、开具扫描（仅 40/70）、红冲。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvoiceServiceImplTest {

    @Mock
    private OrderInvoiceMapper invoiceMapper;
    @Mock
    private OrderQueryService orderQueryService;

    private InvoiceServiceImpl service;

    private static final String NO = "260315011001000001";
    private static final Long USER_ID = 1001L;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderInvoice.class);
    }

    @BeforeEach
    void setUp() {
        service = new InvoiceServiceImpl(invoiceMapper, orderQueryService);
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.COMPLETED));
        when(invoiceMapper.selectOne(any())).thenReturn(null);
        when(orderQueryService.detail(eq(NO), eq(USER_ID), eq(null)))
                .thenReturn(OrderDTO.builder().orderNo(NO).build());
    }

    private Order order(int status) {
        Order o = new Order();
        o.setOrderNo(NO);
        o.setUserId(USER_ID);
        o.setMerchantId(3001L);
        o.setStatus(status);
        return o;
    }

    private InvoiceRequest electronicPersonal() {
        InvoiceRequest req = new InvoiceRequest();
        req.setInvoiceType(1);
        req.setTitleType("PERSONAL");
        req.setEmail("buyer@example.com");
        return req;
    }

    @Test
    void save_newElectronic_insertsPending() {
        service.saveInvoice(NO, USER_ID, electronicPersonal());

        ArgumentCaptor<OrderInvoice> captor = ArgumentCaptor.forClass(OrderInvoice.class);
        verify(invoiceMapper).insert(captor.capture());
        OrderInvoice saved = captor.getValue();
        assertThat(saved.getOrderNo()).isEqualTo(NO);
        assertThat(saved.getStatus()).isZero();
        assertThat(saved.getTitleType()).isEqualTo("PERSONAL");
        assertThat(saved.getEmail()).isEqualTo("buyer@example.com");
    }

    @Test
    void save_cancelledOrder_rejected() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.CANCELLED));
        assertThatThrownBy(() -> service.saveInvoice(NO, USER_ID, electronicPersonal()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode());
    }

    @Test
    void save_notOwner_forbidden() {
        assertThatThrownBy(() -> service.saveInvoice(NO, 2002L, electronicPersonal()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void save_companyMissingTaxNo_rejected() {
        InvoiceRequest req = new InvoiceRequest();
        req.setInvoiceType(2);
        req.setTitleType("COMPANY");
        req.setCompanyName("某公司");
        assertThatThrownBy(() -> service.saveInvoice(NO, USER_ID, req))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    void save_specialVatWithPersonalTitle_rejected() {
        InvoiceRequest req = new InvoiceRequest();
        req.setInvoiceType(2);
        req.setTitleType("PERSONAL");
        req.setEmail("a@b.com");
        assertThatThrownBy(() -> service.saveInvoice(NO, USER_ID, req))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    void save_electronicWithoutEmail_rejected() {
        InvoiceRequest req = new InvoiceRequest();
        req.setInvoiceType(1);
        req.setTitleType("PERSONAL");
        assertThatThrownBy(() -> service.saveInvoice(NO, USER_ID, req))
                .isInstanceOf(BizException.class);
    }

    @Test
    void save_alreadyIssued_cannotModify() {
        OrderInvoice invoice = new OrderInvoice();
        invoice.setId(5L);
        invoice.setStatus(1);
        when(invoiceMapper.selectOne(any())).thenReturn(invoice);
        assertThatThrownBy(() -> service.saveInvoice(NO, USER_ID, electronicPersonal()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode());
        verify(invoiceMapper, never()).updateById(any());
    }

    @Test
    void save_noneType_newOrder_registersNotInvoiceChoice() {
        service.saveInvoice(NO, USER_ID, new InvoiceRequest());

        ArgumentCaptor<OrderInvoice> captor = ArgumentCaptor.forClass(OrderInvoice.class);
        verify(invoiceMapper).insert(captor.capture());
        OrderInvoice saved = captor.getValue();
        assertThat(saved.getOrderNo()).isEqualTo(NO);
        assertThat(saved.getInvoiceType()).isZero();
        assertThat(saved.getStatus()).isZero();
        assertThat(saved.getTitleType()).isEqualTo("PERSONAL");
        verify(invoiceMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void save_noneType_switchFromElectronic_updatesInsteadOfDelete() {
        OrderInvoice invoice = new OrderInvoice();
        invoice.setId(5L);
        invoice.setStatus(0);
        invoice.setInvoiceType(1);
        invoice.setEmail("buyer@example.com");
        when(invoiceMapper.selectOne(any())).thenReturn(invoice);

        service.saveInvoice(NO, USER_ID, new InvoiceRequest());

        ArgumentCaptor<OrderInvoice> captor = ArgumentCaptor.forClass(OrderInvoice.class);
        verify(invoiceMapper).updateById(captor.capture());
        assertThat(captor.getValue().getInvoiceType()).isZero();
        verify(invoiceMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void issueDue_completedOrder_issuesMockPdf() {
        OrderInvoice invoice = new OrderInvoice();
        invoice.setId(5L);
        invoice.setOrderNo(NO);
        invoice.setInvoiceType(1);
        invoice.setEmail("buyer@example.com");
        when(invoiceMapper.selectList(any())).thenReturn(List.of(invoice));
        when(invoiceMapper.markIssued(eq(5L), any(), eq("/invoices/" + NO + ".pdf"), any()))
                .thenReturn(1);

        int count = service.issueDueInvoices();

        assertThat(count).isEqualTo(1);
        verify(invoiceMapper).markIssued(eq(5L), any(), eq("/invoices/" + NO + ".pdf"), any());
    }

    @Test
    void issueDue_orderNotCompleted_skipped() {
        OrderInvoice invoice = new OrderInvoice();
        invoice.setId(6L);
        invoice.setOrderNo(NO);
        invoice.setInvoiceType(1);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(invoice));
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_RECEIVE));

        assertThat(service.issueDueInvoices()).isZero();
        verify(invoiceMapper, never())
                .markIssued(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }

    @Test
    void issueDue_orderMissing_skipped() {
        OrderInvoice invoice = new OrderInvoice();
        invoice.setId(7L);
        invoice.setOrderNo(NO);
        invoice.setInvoiceType(2);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(invoice));
        when(orderQueryService.requireByOrderNo(NO))
                .thenThrow(new BizException(ErrorCode.ORDER_NOT_FOUND, "不存在"));

        assertThat(service.issueDueInvoices()).isZero();
    }

    @Test
    void redFlush_delegatesConditionally() {
        when(invoiceMapper.markRedFlushed(eq(NO), any())).thenReturn(1);
        service.redFlush(NO);
        verify(invoiceMapper).markRedFlushed(eq(NO), any());
    }
}
