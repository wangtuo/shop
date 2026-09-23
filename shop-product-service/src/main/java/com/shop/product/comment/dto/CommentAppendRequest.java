package com.shop.product.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 追评请求：主评后 180 天内仅可追评一次。
 */
@Data
public class CommentAppendRequest implements Serializable {

    /** 追评文字（10-500 字） */
    @NotBlank(message = "追评内容不能为空")
    @Size(min = 10, max = 500, message = "追评内容需 10-500 字")
    private String content;

    /** 追评图片（最多 9 张） */
    @Size(max = 9, message = "追评图片最多 9 张")
    private List<String> images = new ArrayList<>();
}
