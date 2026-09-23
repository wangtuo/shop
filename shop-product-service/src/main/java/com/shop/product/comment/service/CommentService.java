package com.shop.product.comment.service;

import com.shop.common.result.PageResult;
import com.shop.product.comment.dto.CommentAppendRequest;
import com.shop.product.comment.dto.CommentCreateRequest;
import com.shop.product.comment.dto.CommentReplyRequest;
import com.shop.product.comment.dto.CommentVO;
import com.shop.common.result.PageQuery;

/**
 * 商品评价服务（design 3.5）。
 */
public interface CommentService {

    /** 发表评价（订单完成 15 天内，一订单一 SKU 一条；敏感词过滤；好评率落 SPU 冗余）。 */
    Long create(CommentCreateRequest request, Long userId);

    /** 追评（评价后 180 天内一次）。 */
    void append(Long commentId, CommentAppendRequest request, Long userId);

    /** 商家回复（每条一次，归属鉴权）。 */
    void reply(Long commentId, CommentReplyRequest request);

    /** SPU 评价分页（正常状态）。 */
    PageResult<CommentVO> pageBySpu(Long spuId, PageQuery query);
}
