package com.shop.product.comment.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 商品评价：一条主评（三维度星级 + 文字 + 图/视频）+ 最多一次追评 + 一次商家回复。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product_comment")
public class ProductComment extends BaseEntity {

    /** 评价展示单号 CM+雪花 */
    private String commentNo;

    /** 业务订单号（完成 15 天内可评） */
    private String orderNo;

    /** 评价用户 ID */
    private Long userId;

    /** 商家 ID（回复归属鉴权） */
    private Long merchantId;

    /** SPU ID */
    private Long spuId;

    /** SKU ID */
    private Long skuId;

    /** 商品质量星级 1-5 */
    private Integer qualityStar;

    /** 物流服务星级 1-5 */
    private Integer logisticsStar;

    /** 服务态度星级 1-5 */
    private Integer serviceStar;

    /** 评价文字 10-500 字（敏感词过滤后） */
    private String content;

    /** 评价图片 JSON 数组（≤9 张） */
    private String imagesJson;

    /** 评价视频 URL（≤1 个） */
    private String videoUrl;

    /** 视频时长（秒，≤30） */
    private Integer videoDurationSec;

    /** 追评文字 */
    private String appendContent;

    /** 追评时间（非空表示已追评，仅一次） */
    private LocalDateTime appendTime;

    /** 商家回复内容（仅一次） */
    private String replyContent;

    /** 商家回复时间 */
    private LocalDateTime replyTime;

    /** 状态：1 正常 2 平台屏蔽 */
    private Integer status;
}
