package com.shop.product.comment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.product.event.CommentCreatedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageQuery;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.common.util.JsonUtils;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.product.comment.dto.CommentAppendRequest;
import com.shop.product.comment.dto.CommentCreateRequest;
import com.shop.product.comment.dto.CommentReplyRequest;
import com.shop.product.comment.dto.CommentVO;
import com.shop.product.comment.entity.ProductComment;
import com.shop.product.comment.mapper.ProductCommentMapper;
import com.shop.product.comment.service.CommentService;
import com.shop.product.comment.service.SensitiveWordService;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.mapper.ProductSkuMapper;
import com.shop.product.goods.mapper.ProductSpuMapper;
import com.shop.product.support.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 评价服务实现（design 3.5）：
 * 完成时效通过 OrderClient.getByOrderNo 取订单完成时间校验（OrderDTO 暂无专门完成时间字段，
 * 已完成订单取 updateTime，契约缺口见交付报告）。
 */
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /** 订单完成后可评价天数 */
    static final int COMMENT_WINDOW_DAYS = 15;
    /** 追评窗口天数 */
    static final int APPEND_WINDOW_DAYS = 180;
    /** 好评综合星级门槛 */
    static final int GOOD_STAR = 4;
    /** 图片上限（注解兜底，服务内再校验一次） */
    static final int MAX_IMAGES = 9;
    /** 视频时长上限（秒） */
    static final int MAX_VIDEO_SECONDS = 30;
    /** 评价状态：1 正常（仅正常态对外发布评价事件） */
    private static final int STATUS_NORMAL = 1;
    /** 行为类型：1 评价 2 晒单 */
    private static final int BEHAVIOR_COMMENT = 1;
    private static final int BEHAVIOR_SHARE = 2;
    private static final int ORDER_STATUS_COMPLETED = 40;

    private final ProductCommentMapper commentMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductSpuMapper spuMapper;
    private final OrderClient orderClient;
    private final SensitiveWordService sensitiveWordService;
    private final IdGenerator idGenerator;
    private final OutboxPublisher outboxPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CommentCreateRequest request, Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        validateMedia(request.getImages(), request.getVideoDurationSec());

        OrderDTO order = getCompletedOrder(request.getOrderNo(), userId);
        LocalDateTime finishTime = resolveFinishTime(order);
        if (finishTime == null || ChronoUnit.DAYS.between(finishTime, LocalDateTime.now()) > COMMENT_WINDOW_DAYS
                || finishTime.isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "订单完成 " + COMMENT_WINDOW_DAYS + " 天内可评价，当前已超期或订单未完成");
        }

        ProductSku sku = skuMapper.selectById(request.getSkuId());
        if (sku == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "SKU 不存在");
        }
        Long exists = commentMapper.selectCount(new LambdaQueryWrapper<ProductComment>()
                .eq(ProductComment::getOrderNo, request.getOrderNo())
                .eq(ProductComment::getSkuId, request.getSkuId()));
        if (exists > 0) {
            throw new BizException(ErrorCode.REPEAT_SUBMIT, "该订单商品已评价，请勿重复评价");
        }

        String filteredContent = sensitiveWordService.checkAndFilter(request.getContent());

        ProductComment comment = new ProductComment();
        comment.setCommentNo("CM" + idGenerator.nextIdString());
        comment.setOrderNo(request.getOrderNo());
        comment.setUserId(userId);
        comment.setMerchantId(sku.getMerchantId());
        comment.setSpuId(sku.getSpuId());
        comment.setSkuId(request.getSkuId());
        comment.setQualityStar(request.getQualityStar());
        comment.setLogisticsStar(request.getLogisticsStar());
        comment.setServiceStar(request.getServiceStar());
        comment.setContent(filteredContent);
        comment.setImagesJson(JsonUtils.toJson(request.getImages()));
        comment.setVideoUrl(request.getVideoUrl());
        comment.setVideoDurationSec(request.getVideoDurationSec() == null ? 0 : request.getVideoDurationSec());
        comment.setStatus(1);
        commentMapper.insert(comment);

        // 好评率冗余：综合星级（三维度平均）≥4 计入好评
        int overall = overallStar(request.getQualityStar(), request.getLogisticsStar(), request.getServiceStar());
        int goodFlag = overall >= GOOD_STAR ? 1 : 0;
        spuMapper.increaseCommentCount(sku.getSpuId(), goodFlag);

        // C-COMMENT：insert + 好评率更新之后、同一 @Transactional 内登记 outbox（随业务提交/回滚）。
        // 仅正常态发布；MASTER 裁决①：带图评价仍是评价(1) withImage=true，晒单(2)走 append() 入口。
        if (comment.getStatus() != null && comment.getStatus() == STATUS_NORMAL) {
            boolean withImage = request.getImages() != null && !request.getImages().isEmpty();
            publishCommentEvent(comment, BEHAVIOR_COMMENT, withImage);
        }
        return comment.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void append(Long commentId, CommentAppendRequest request, Long userId) {
        ProductComment comment = requireComment(commentId);
        if (userId == null || !userId.equals(comment.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能追评自己的评价");
        }
        if (comment.getAppendTime() != null) {
            throw new BizException(ErrorCode.REPEAT_SUBMIT, "该评价已追评，仅可追评一次");
        }
        validateMedia(request.getImages(), null);
        long days = ChronoUnit.DAYS.between(comment.getCreateTime(), LocalDateTime.now());
        if (days > APPEND_WINDOW_DAYS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "评价后 " + APPEND_WINDOW_DAYS + " 天内可追评，当前已超期");
        }
        comment.setAppendContent(sensitiveWordService.checkAndFilter(request.getContent()));
        comment.setAppendTime(LocalDateTime.now());
        commentMapper.updateById(comment);

        // C-COMMENT：晒单(2)独立入口，同事务登记事件；主评被屏蔽（status=2）则不发、不补发。
        if (comment.getStatus() != null && comment.getStatus() == STATUS_NORMAL) {
            boolean withImage = request.getImages() != null && !request.getImages().isEmpty();
            publishCommentEvent(comment, BEHAVIOR_SHARE, withImage);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reply(Long commentId, CommentReplyRequest request) {
        long merchantId = AuthUtils.requireMerchantId();
        ProductComment comment = requireComment(commentId);
        if (comment.getMerchantId() == null || comment.getMerchantId() != merchantId) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能回复本店商品的评价");
        }
        if (comment.getReplyTime() != null) {
            throw new BizException(ErrorCode.REPEAT_SUBMIT, "该评价已回复，仅可回复一次");
        }
        comment.setReplyContent(sensitiveWordService.checkAndFilter(request.getContent()));
        comment.setReplyTime(LocalDateTime.now());
        commentMapper.updateById(comment);
    }

    @Override
    public PageResult<CommentVO> pageBySpu(Long spuId, PageQuery query) {
        Page<ProductComment> page = new Page<>(query.safePageNum(), query.safePageSize());
        Page<ProductComment> result = commentMapper.selectPage(page,
                new LambdaQueryWrapper<ProductComment>()
                        .eq(ProductComment::getSpuId, spuId)
                        .eq(ProductComment::getStatus, 1)
                        .orderByDesc(ProductComment::getId));
        List<CommentVO> list = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(query.safePageNum(), query.safePageSize(), result.getTotal(), list);
    }

    // ------------------------------------------------------------------
    // 校验/装配
    // ------------------------------------------------------------------

    /**
     * 同事务登记评价/晒单事件（C-COMMENT）。
     *
     * <p>R4-25：主评（behaviorType=1）与追评/晒单（behaviorType=2）作用于<b>同一行</b>评论、
     * 共用同一 commentNo，但分属两个独立事务（追评在主评后 180 天内）。outbox 唯一键
     * uk_topic_tag_bizkey 行投递后永不删除，若两次都以裸 commentNo 为 bizKey，追评事务的
     * INSERT 必抛 DuplicateKeyException 回滚——追评 100% 失败，晒单成长值永远发不出去。
     * 故事务性 outbox 键追加 {@code #b}{behaviorType}；消息体 bizNo 仍为裸 commentNo，
     * 消费端按 eventId/commentId（积分按 COMMENT:/SHOW: 前缀）幂等的口径完全不变。</p>
     */
    private void publishCommentEvent(ProductComment comment, int behaviorType, boolean withImage) {
        CommentCreatedEvent event = CommentCreatedEvent.builder()
                .commentId(comment.getId())
                .orderNo(comment.getOrderNo())
                .userId(comment.getUserId())
                .spuId(comment.getSpuId())
                .skuId(comment.getSkuId())
                .behaviorType(behaviorType)
                .withImage(withImage)
                .eventTime(System.currentTimeMillis())
                .build();
        event.setBizNo(comment.getCommentNo());
        outboxPublisher.publish(MqTopics.COMMENT_CREATED, null, event,
                comment.getCommentNo() + "#b" + behaviorType);
    }

    private OrderDTO getCompletedOrder(String orderNo, Long userId) {
        Result<OrderDTO> result;
        try {
            result = orderClient.getByOrderNo(orderNo);
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL, "订单服务查询失败，暂时无法评价", e);
        }
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在：" + orderNo);
        }
        OrderDTO order = result.getData();
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能评价自己的订单");
        }
        if (order.getStatus() == null || order.getStatus() != ORDER_STATUS_COMPLETED) {
            throw new BizException(ErrorCode.CONFLICT, "订单未完成，暂不能评价");
        }
        return order;
    }

    /**
     * 取订单完成时间。OrderDTO 未提供独立完成时间字段（契约缺口），已完成订单以 updateTime 为准，
     * 缺失时退化为支付时间。
     */
    private LocalDateTime resolveFinishTime(OrderDTO order) {
        if (order.getUpdateTime() != null) {
            return order.getUpdateTime();
        }
        return order.getPayTime();
    }

    private void validateMedia(List<String> images, Integer videoDurationSec) {
        if (images != null && images.size() > MAX_IMAGES) {
            throw new BizException(ErrorCode.PARAM_INVALID, "评价图片最多 " + MAX_IMAGES + " 张");
        }
        if (videoDurationSec != null && videoDurationSec > MAX_VIDEO_SECONDS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "评价视频时长不能超过 " + MAX_VIDEO_SECONDS + " 秒");
        }
    }

    /** 综合星级：三维度算术平均后四舍五入。 */
    static int overallStar(int quality, int logistics, int service) {
        return Math.round((quality + logistics + service) / 3.0f);
    }

    @SuppressWarnings("unchecked")
    private CommentVO toVO(ProductComment c) {
        CommentVO vo = new CommentVO();
        vo.setId(c.getId());
        vo.setCommentNo(c.getCommentNo());
        vo.setOrderNo(c.getOrderNo());
        vo.setUserId(c.getUserId());
        vo.setSpuId(c.getSpuId());
        vo.setSkuId(c.getSkuId());
        vo.setQualityStar(c.getQualityStar());
        vo.setLogisticsStar(c.getLogisticsStar());
        vo.setServiceStar(c.getServiceStar());
        vo.setOverallStar(overallStar(c.getQualityStar(), c.getLogisticsStar(), c.getServiceStar()));
        vo.setContent(c.getContent());
        if (c.getImagesJson() != null && !c.getImagesJson().isBlank()) {
            List<String> images = JsonUtils.fromJson(c.getImagesJson(), List.class);
            if (images != null) {
                vo.setImages((List<String>) images);
            }
        }
        vo.setVideoUrl(c.getVideoUrl());
        vo.setVideoDurationSec(c.getVideoDurationSec());
        vo.setAppendContent(c.getAppendContent());
        vo.setAppendTime(c.getAppendTime());
        vo.setReplyContent(c.getReplyContent());
        vo.setReplyTime(c.getReplyTime());
        vo.setCreateTime(c.getCreateTime());
        return vo;
    }

    private ProductComment requireComment(Long commentId) {
        ProductComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评价不存在");
        }
        return comment;
    }
}
