package com.shop.marketing.common.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 平台审批驳回请求体（驳回原因必填）。 */
@Data
public class AuditRejectRequest {

    @NotBlank(message = "驳回原因不能为空")
    @Size(max = 200, message = "驳回原因最长200字")
    private String remark;
}
