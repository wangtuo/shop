package com.shop.order.support;

import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderItemDTO;
import com.shop.api.order.event.OrderItemMessage;
import com.shop.order.invoice.entity.OrderInvoice;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体 → DTO / 事件消息组装单测。
 */
class OrderAssemblerTest {

    private final OrderAssembler assembler = new OrderAssembler();

    private OrderItem item() {
        OrderItem i = new OrderItem();
        i.setId(9L);
        i.setOrderNo("O1");
        i.setUserId(1001L);
        i.setSkuId(11L);
        i.setSpuId(21L);
        i.setMerchantId(31L);
        i.setShopId(41L);
        i.setCategory3Id(51L);
        i.setSkuName("红色 M");
        i.setSpecText("颜色:红;尺码:M");
        i.setPriceFen(9900L);
        i.setQty(2);
        i.setItemTotalFen(19800L);
        i.setDiscountAllocFen(1800L);
        i.setPaidFen(18000L);
        i.setAftersaleStatus(0);
        i.setStockType(1);
        i.setGroupNo("G1");
        return i;
    }

    @Test
    void toItemDTO_mapsSnapshotAndAmounts() {
        OrderItemDTO dto = assembler.toItemDTO(item());
        assertThat(dto.getOrderItemId()).isEqualTo(9L);
        assertThat(dto.getSkuId()).isEqualTo(11L);
        assertThat(dto.getPriceFen()).isEqualTo(9900L);
        assertThat(dto.getItemTotalFen()).isEqualTo(19800L);
        assertThat(dto.getPaidFen()).isEqualTo(18000L);
    }

    @Test
    void toMessage_mapsEventFields() {
        OrderItemMessage msg = assembler.toMessage(item());
        assertThat(msg.getSkuId()).isEqualTo(11L);
        assertThat(msg.getCategory3Id()).isEqualTo(51L);
        assertThat(msg.getQty()).isEqualTo(2);
        assertThat(msg.getSalePriceFen()).isEqualTo(9900L);
        assertThat(msg.getGroupNo()).isEqualTo("G1");
    }

    @Test
    void toMessages_preservesOrder() {
        List<OrderItemMessage> msgs = assembler.toMessages(List.of(item(), item()));
        assertThat(msgs).hasSize(2);
    }

    @Test
    void toDTO_nullInvoiceAndItems_mapsSafely() {
        Order o = new Order();
        o.setOrderNo("O1");
        o.setUserId(1001L);
        o.setStatus(10);
        o.setReceiver("张三");
        o.setReceiverPhone("13800000000");
        o.setProvince("浙江省");

        OrderDTO dto = assembler.toDTO(o, null, null);
        assertThat(dto.getOrderNo()).isEqualTo("O1");
        assertThat(dto.getItems()).isEmpty();
        assertThat(dto.getInvoice()).isNull();
        assertThat(dto.getReceiver().getReceiver()).isEqualTo("张三");
        assertThat(dto.getReceiver().getPhone()).isEqualTo("13800000000");
    }

    @Test
    void toInvoiceDTO_mapsFields() {
        OrderInvoice inv = new OrderInvoice();
        inv.setInvoiceType(2);
        inv.setTitleType("COMPANY");
        inv.setCompanyName("某某有限公司");
        inv.setTaxNo("91330000XXXXX");
        inv.setEmail("tax@corp.com");
        assertThat(assembler.toInvoiceDTO(inv).getTaxNo()).isEqualTo("91330000XXXXX");
    }

    @Test
    void toInvoiceDTO_mapsIssueFields() {
        OrderInvoice inv = new OrderInvoice();
        inv.setInvoiceType(0);
        inv.setTitleType("PERSONAL");
        inv.setStatus(0);
        assertThat(assembler.toInvoiceDTO(inv).getStatus()).isZero();

        java.time.LocalDateTime issuedAt = java.time.LocalDateTime.of(2026, 9, 17, 4, 10);
        inv.setStatus(1);
        inv.setInvoiceNo("INV2026091704100001");
        inv.setPdfUrl("/invoices/O1.pdf");
        inv.setIssueTime(issuedAt);
        com.shop.api.order.dto.InvoiceDTO dto = assembler.toInvoiceDTO(inv);
        assertThat(dto.getStatus()).isEqualTo(1);
        assertThat(dto.getInvoiceNo()).isEqualTo("INV2026091704100001");
        assertThat(dto.getPdfUrl()).isEqualTo("/invoices/O1.pdf");
        assertThat(dto.getIssueTime()).isEqualTo(issuedAt);
    }
}
