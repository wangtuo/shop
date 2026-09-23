package com.shop.api.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * SPU（标准产品单元）对外契约。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpuDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SPU ID */
    private Long spuId;

    /** 商家 ID */
    private Long merchantId;

    /** 店铺 ID */
    private Long shopId;

    /** 商品名称 */
    private String name;

    /** 品牌 ID */
    private Long brandId;

    /** 三级类目 ID */
    private Long category3Id;

    /** 主图 URL */
    private String mainImage;

    /** 轮播图 URL 列表 */
    @Builder.Default
    private List<String> images = new ArrayList<>();

    /** 商品状态，取值见 GoodsStatuses（0 草稿 … 7 已删除） */
    private Integer status;

    /** 累计销量 */
    private Long sales;

    /** 好评数（4 星 + 5 星） */
    private Long goodCommentCount;

    /** 总评价数 */
    private Long totalCommentCount;

    /** 好评率 = 好评数 / 总评价数（design 3.5） */
    private BigDecimal goodRate;
}
