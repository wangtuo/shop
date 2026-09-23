package com.shop.product.goods.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 平台审核请求。
 */
@Data
public class SpuAuditRequest implements Serializable {

    /** true 审核通过（→ 已上架），false 拒绝（→ 审核拒绝） */
    @NotNull(message = "审核结论不能为空")
    private Boolean pass;

    /** 审核备注 / 拒绝原因 */
    @Size(max = 200, message = "审核备注最长 200 字")
    private String remark;
}
