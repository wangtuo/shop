package com.shop.product.goods.dto;

import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.dto.SpuDTO;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * SPU 详情聚合：SPU 信息 + 详情 JSON + SKU 列表（C 端 Redis 缓存对象）。
 */
@Data
public class SpuDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SPU 主体信息 */
    private SpuDTO spu;

    /** 商品详情 JSON */
    private String detailJson;

    /** SPU 属性键值 JSON */
    private String attrsJson;

    /** SKU 列表 */
    private List<SkuDTO> skus = new ArrayList<>();
}
