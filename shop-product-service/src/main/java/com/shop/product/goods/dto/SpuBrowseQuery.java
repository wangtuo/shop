package com.shop.product.goods.dto;

import com.shop.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * C 端商品浏览分页：按三级类目 / 关键词。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SpuBrowseQuery extends PageQuery {

    /** 三级类目 ID */
    private Long category3Id;

    /** 搜索关键词（匹配商品名称） */
    private String keyword;
}
