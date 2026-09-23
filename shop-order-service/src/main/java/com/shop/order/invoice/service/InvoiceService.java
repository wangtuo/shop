package com.shop.order.invoice.service;

import com.shop.api.order.dto.OrderDTO;
import com.shop.order.order.dto.InvoiceRequest;

/**
 * 发票服务（design 5.5）。
 */
public interface InvoiceService {

    /** 提交/修改发票信息（订单完成开具前可改）。 */
    OrderDTO saveInvoice(String orderNo, Long userId, InvoiceRequest request);

    /** 查询订单发票（随订单聚合返回）。 */
    OrderDTO view(String orderNo, Long userId);

    /** 定时扫描：为已完成订单待开具的发票模拟开具 PDF。 */
    int issueDueInvoices();

    /** REFUND_SUCCESS 全额退款时自动冲红。 */
    void redFlush(String orderNo);
}
