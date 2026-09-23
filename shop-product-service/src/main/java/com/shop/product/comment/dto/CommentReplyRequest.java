package com.shop.product.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家回复请求：每条评价仅可回复一次。
 */
@Data
public class CommentReplyRequest implements Serializable {

    @NotBlank(message = "回复内容不能为空")
    @Size(max = 300, message = "回复内容最长 300 字")
    private String content;
}
