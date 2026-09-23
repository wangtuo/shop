package com.shop.product.comment.controller;

import com.shop.common.result.PageQuery;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.web.Anonymous;
import com.shop.framework.web.UserContext;
import com.shop.product.comment.dto.CommentAppendRequest;
import com.shop.product.comment.dto.CommentCreateRequest;
import com.shop.product.comment.dto.CommentReplyRequest;
import com.shop.product.comment.dto.CommentVO;
import com.shop.product.comment.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评价 HTTP：买家发表/追评需登录；商家回复走 /merchant 前缀（服务内归属鉴权）；
 * SPU 评价列表匿名可见。
 */
@RestController
@RequestMapping("/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /** 发表评价（订单完成 15 天内） */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody CommentCreateRequest request) {
        return Result.success(commentService.create(request, UserContext.getUserId()));
    }

    /** 追评（180 天内一次） */
    @PostMapping("/{commentId}/append")
    public Result<Void> append(@PathVariable Long commentId,
                               @Valid @RequestBody CommentAppendRequest request) {
        commentService.append(commentId, request, UserContext.getUserId());
        return Result.success();
    }

    /** 商家回复（每条一次，归属鉴权） */
    @PostMapping("/merchant/{commentId}/reply")
    public Result<Void> reply(@PathVariable Long commentId,
                              @Valid @RequestBody CommentReplyRequest request) {
        commentService.reply(commentId, request);
        return Result.success();
    }

    /** SPU 评价分页（匿名浏览） */
    @Anonymous
    @GetMapping("/products/{spuId}")
    public Result<PageResult<CommentVO>> pageBySpu(@PathVariable Long spuId,
                                                   @ModelAttribute PageQuery query) {
        return Result.success(commentService.pageBySpu(spuId, query));
    }
}
