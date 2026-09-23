package com.shop.product.goods.dto;

import com.shop.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商户端商品管理分页查询（merchantId 强制取登录上下文，忽略前端传入）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SpuManageQuery extends PageQuery {

    /** 商品名称模糊 */
    private String keyword;

    /** 商品状态 0-7 */
    private Integer status;

    /** 三级类目过滤 */
    private Long category3Id;
}
