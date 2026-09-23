package com.shop.order.support;

import com.shop.api.order.dto.InvoiceDTO;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderItemDTO;
import com.shop.api.order.dto.ReceiverDTO;
import com.shop.api.order.event.OrderItemMessage;
import com.shop.order.invoice.entity.OrderInvoice;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单实体与对外 DTO / 事件消息的组装（无业务规则，纯映射）。
 */
@Component
public class OrderAssembler {

    /** 组装订单聚合 DTO（Feign 内部接口与 HTTP 详情共用）。 */
    public OrderDTO toDTO(Order o, List<OrderItem> items, OrderInvoice invoice) {
        return OrderDTO.builder()
                .orderNo(o.getOrderNo())
                .userId(o.getUserId())
                .userNickname(o.getUserNickname())
                .userPhone(o.getUserPhone())
                .orderType(o.getOrderType())
                .status(o.getStatus())
                .source(o.getSource())
                .productTotalFen(o.getProductTotalFen())
                .freightFen(o.getFreightFen())
                .productDiscountFen(o.getProductDiscountFen())
                .shopDiscountFen(o.getShopDiscountFen())
                .platformDiscountFen(o.getPlatformDiscountFen())
                .pointsDeductFen(o.getPointsDeductFen())
                .discountTotalFen(o.getDiscountTotalFen())
                .payFen(o.getPayFen())
                .hasFreightInsurance(o.getHasFreightInsurance())
                .insurancePremiumFen(o.getInsurancePremiumFen())
                .payMethod(o.getPayMethod())
                .payTransactionNo(o.getPayTransactionNo())
                .payTime(o.getPayTime())
                .shipTime(o.getShipTime())
                .logisticsCompany(o.getLogisticsCompany())
                .logisticsNo(o.getLogisticsNo())
                .confirmTime(o.getConfirmTime())
                .completeTime(o.getCompleteTime())
                .remark(o.getRemark())
                .receiver(ReceiverDTO.builder()
                        .receiver(o.getReceiver())
                        .phone(o.getReceiverPhone())
                        .province(o.getProvince())
                        .city(o.getCity())
                        .district(o.getDistrict())
                        .detailAddress(o.getDetailAddress())
                        .build())
                .invoice(invoice == null ? null : toInvoiceDTO(invoice))
                .items(items == null ? List.of() : items.stream().map(this::toItemDTO).toList())
                .createTime(o.getCreateTime())
                .updateTime(o.getUpdateTime())
                .build();
    }

    public OrderItemDTO toItemDTO(OrderItem i) {
        return OrderItemDTO.builder()
                .orderItemId(i.getId())
                .orderNo(i.getOrderNo())
                .skuId(i.getSkuId())
                .spuId(i.getSpuId())
                .merchantId(i.getMerchantId())
                .shopId(i.getShopId())
                .skuName(i.getSkuName())
                .specText(i.getSpecText())
                .image(i.getImage())
                .priceFen(i.getPriceFen())
                .qty(i.getQty())
                .itemTotalFen(i.getItemTotalFen())
                .discountAllocFen(i.getDiscountAllocFen())
                .pointsAllocFen(i.getPointsAllocFen())
                .freightAllocFen(i.getFreightAllocFen())
                .paidFen(i.getPaidFen())
                .aftersaleStatus(i.getAftersaleStatus())
                .build();
    }

    public InvoiceDTO toInvoiceDTO(OrderInvoice inv) {
        return InvoiceDTO.builder()
                .invoiceType(inv.getInvoiceType())
                .contentScope(inv.getContentScope())
                .titleType(inv.getTitleType())
                .companyName(inv.getCompanyName())
                .taxNo(inv.getTaxNo())
                .email(inv.getEmail())
                .status(inv.getStatus())
                .invoiceNo(inv.getInvoiceNo())
                .pdfUrl(inv.getPdfUrl())
                .issueTime(inv.getIssueTime())
                .build();
    }

    /** 组装 ORDER_* 事件的明细消息体。 */
    public OrderItemMessage toMessage(OrderItem i) {
        return OrderItemMessage.builder()
                .skuId(i.getSkuId())
                .spuId(i.getSpuId())
                .merchantId(i.getMerchantId())
                .shopId(i.getShopId())
                .category3Id(i.getCategory3Id())
                .qty(i.getQty())
                .salePriceFen(i.getPriceFen())
                .productTotalFen(i.getItemTotalFen())
                .paidFen(i.getPaidFen())
                .seckillActivityId(i.getSeckillActivityId())
                .groupNo(i.getGroupNo())
                .presaleActivityId(i.getPresaleActivityId())
                .build();
    }

    public List<OrderItemMessage> toMessages(List<OrderItem> items) {
        return items.stream().map(this::toMessage).toList();
    }
}
