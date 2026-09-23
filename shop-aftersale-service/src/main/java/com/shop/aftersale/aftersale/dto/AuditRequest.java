package com.shop.aftersale.aftersale.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家审核请求。
 */
@Data
public class AuditRequest implements Serializable {
    @NotNull(message = "审核结论不能为空")
    private Boolean agree;
    /** 拒绝原因（agree=false 必填，服务端校验） */
    private String rejectReason;
}
