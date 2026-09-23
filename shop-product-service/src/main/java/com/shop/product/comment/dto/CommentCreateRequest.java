package com.shop.product.comment.dto;

import com.shop.common.validation.MediaUrls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 发表评价请求（订单完成 15 天内，每订单每 SKU 一条）。
 */
@Data
public class CommentCreateRequest implements Serializable {

    /** 业务订单号 */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /** SPU ID */
    @NotNull(message = "SPU ID 不能为空")
    private Long spuId;

    /** SKU ID */
    @NotNull(message = "SKU ID 不能为空")
    private Long skuId;

    /** 商品质量星级 1-5 */
    @NotNull(message = "商品质量星级必填")
    @Min(value = 1, message = "星级最低 1")
    @Max(value = 5, message = "星级最高 5")
    private Integer qualityStar;

    /** 物流服务星级 1-5 */
    @NotNull(message = "物流服务星级必填")
    @Min(1)
    @Max(5)
    private Integer logisticsStar;

    /** 服务态度星级 1-5 */
    @NotNull(message = "服务态度星级必填")
    @Min(1)
    @Max(5)
    private Integer serviceStar;

    /** 评价文字（10-500 字） */
    @NotBlank(message = "评价内容不能为空")
    @Size(min = 10, max = 500, message = "评价内容需 10-500 字")
    private String content;

    /** 评价图片（服务端 validateMedia 保持 9 张业务上限；此处为 W0 媒体契约：≤10 张、http/https、≤512） */
    @Valid
    @Size(max = MediaUrls.LIST_MAX, message = "评价图片最多 " + MediaUrls.LIST_MAX + " 张")
    private List<@Pattern(regexp = MediaUrls.URL_PATTERN, message = "图片地址必须为 http/https URL")
            @Size(max = MediaUrls.URL_MAX_LENGTH, message = "图片地址长度不能超过 512") String> images = new ArrayList<>();

    /** 评价视频 URL（最多 1 个；http/https、≤512） */
    @Pattern(regexp = MediaUrls.URL_PATTERN, message = "视频地址必须为 http/https URL")
    @Size(max = MediaUrls.URL_MAX_LENGTH, message = "视频地址长度不能超过 512")
    private String videoUrl;

    /** 视频时长（秒，≤30） */
    @Max(value = 30, message = "评价视频时长不能超过 30 秒")
    @Min(value = 0)
    private Integer videoDurationSec;
}
