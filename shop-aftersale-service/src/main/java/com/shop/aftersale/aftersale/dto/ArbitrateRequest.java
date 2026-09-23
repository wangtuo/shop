package com.shop.aftersale.aftersale.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 平台仲裁请求。
 */
@Data
public class ArbitrateRequest implements Serializable {
    /** 1 商家胜诉 2 买家胜诉 3 部分支持 */
    @NotNull(message = "仲裁结果不能为空")
    private Integer result;
    /** 部分支持时的裁定退款金额（分） */
    private Long awardFen;
    private String remark;
}
