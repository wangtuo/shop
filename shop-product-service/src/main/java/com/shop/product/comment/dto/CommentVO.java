package com.shop.product.comment.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 评价展示 VO。
 */
@Data
public class CommentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String commentNo;
    private String orderNo;
    private Long userId;
    private Long spuId;
    private Long skuId;
    private Integer qualityStar;
    private Integer logisticsStar;
    private Integer serviceStar;
    /** 综合星级（三维度四舍五入平均） */
    private Integer overallStar;
    private String content;
    private List<String> images = new ArrayList<>();
    private String videoUrl;
    private Integer videoDurationSec;
    private String appendContent;
    private LocalDateTime appendTime;
    private String replyContent;
    private LocalDateTime replyTime;
    private LocalDateTime createTime;
}
