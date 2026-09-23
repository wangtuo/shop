package com.shop.order.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 发票提交内容（design 5.5）。下单携带或待发货前补开均使用本对象。
 */
@Data
public class InvoiceRequest implements Serializable {

    /** 0 不开 1 电子普票 2 专票 */
    private Integer invoiceType = 0;
    /** 1 商品明细 2 商品类别 */
    private Integer contentScope = 1;
    /** PERSONAL / COMPANY */
    @NotBlank(message = "抬头类型不能为空")
    private String titleType = "PERSONAL";
    /** 企业名称（企业抬头） */
    private String companyName;
    /** 税号（企业抬头） */
    private String taxNo;
    /** 电子发票接收邮箱 */
    @Email(message = "邮箱格式不正确")
    private String email;
}
