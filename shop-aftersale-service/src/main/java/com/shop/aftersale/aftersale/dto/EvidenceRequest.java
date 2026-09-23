package com.shop.aftersale.aftersale.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 介入举证请求。
 */
@Data
public class EvidenceRequest implements Serializable {
    /** 1 图片 2 视频 3 文字 */
    @NotNull(message = "凭证类型不能为空")
    private Integer evidenceType;
    private String content;
    /** 图片/视频 URL，逗号分隔 */
    private String mediaUrls;
}
