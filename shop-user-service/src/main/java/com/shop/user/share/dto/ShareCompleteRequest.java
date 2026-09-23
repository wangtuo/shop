package com.shop.user.share.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 分享完成回调请求（C43）：客户端在分享动作完成时上报，requestNo 由客户端生成（UUID），网络重试复用同号。
 */
@Data
public class ShareCompleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 客户端幂等号（UUID），重试复用 */
    @NotBlank(message = "幂等号不能为空")
    @Size(max = 64, message = "幂等号长度不能超过 64")
    private String requestNo;

    /** 分享目标类型：1 商品 2 活动 3 拼团 …（1-9 软约束，白名单不做硬校验） */
    @NotNull(message = "分享目标类型不能为空")
    @Min(value = 1, message = "分享目标类型取值非法")
    @Max(value = 9, message = "分享目标类型取值非法")
    private Integer targetType;

    /** 目标 ID（可空） */
    @Size(max = 64, message = "目标ID长度不能超过 64")
    private String targetId;
}
