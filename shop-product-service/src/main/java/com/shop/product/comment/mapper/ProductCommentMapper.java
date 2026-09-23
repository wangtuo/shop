package com.shop.product.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.product.comment.entity.ProductComment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品评价 Mapper。
 */
@Mapper
public interface ProductCommentMapper extends BaseMapper<ProductComment> {
}
