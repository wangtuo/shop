package com.shop.order.invoice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.order.invoice.entity.OrderInvoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OrderInvoiceMapper extends BaseMapper<OrderInvoice> {

    /** 订单完成后扫描开具：0 待开具 → 1 已开具（模拟生成发票号与 PDF）。 */
    @Update("UPDATE t_order_invoice SET status = 1, invoice_no = #{invoiceNo}, pdf_url = #{pdfUrl}, "
            + "issue_time = #{issueTime} WHERE id = #{id} AND status = 0 AND deleted = 0")
    int markIssued(@Param("id") Long id, @Param("invoiceNo") String invoiceNo, @Param("pdfUrl") String pdfUrl,
                   @Param("issueTime") LocalDateTime issueTime);

    /** 退款成功自动冲红：1 已开具 → 2 已冲红（全额退款时）。 */
    @Update("UPDATE t_order_invoice SET status = 2, red_flush_time = #{redFlushTime} "
            + "WHERE order_no = #{orderNo} AND status = 1 AND deleted = 0")
    int markRedFlushed(@Param("orderNo") String orderNo, @Param("redFlushTime") LocalDateTime redFlushTime);
}
