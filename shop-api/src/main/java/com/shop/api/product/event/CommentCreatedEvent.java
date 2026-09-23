package com.shop.api.product.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 评价/晒单创建事件（Topic：{@code MqTopics.COMMENT_CREATED}）。
 *
 * <p>商品域评价审核/创建成功后生产（评价被删除/隐藏不补发撤销事件）；用户域消费以发放评价/晒单成长值与积分。
 * 消费端以 eventId/commentId 幂等。
 *
 * <p>唯一形态以 GAP_PLAN_MASTER §2.1 裁决 1 / GAP_PLAN_USER C42 为准（orderNo 全系统为 String）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class CommentCreatedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 评价单 ID */
    private Long commentId;

    /** 评价关联订单号（String，18 位；可空） */
    private String orderNo;

    /** 评价用户 ID */
    private Long userId;

    /** SPU ID */
    private Long spuId;

    /** SKU ID（可空：无规格商品/晒单场景） */
    private Long skuId;

    /** 行为类型：1 评价 2 晒单 */
    private Integer behaviorType;

    /** 是否带图（带图/视频评价判定晒单激励时参考） */
    private Boolean withImage;

    /** 事件产生时间（epoch 毫秒） */
    private Long eventTime;
}
