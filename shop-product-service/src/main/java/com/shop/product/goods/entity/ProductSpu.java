package com.shop.product.goods.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SPU。八态 status 见 {@link com.shop.api.product.enums.GoodsStatuses}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product_spu")
public class ProductSpu extends BaseEntity {

    /** 所属商家 ID */
    private Long merchantId;

    /** 所属店铺 ID */
    private Long shopId;

    /** 商品名称 */
    private String name;

    /** 品牌 ID */
    private Long brandId;

    /** 三级类目 ID */
    private Long category3Id;

    /** 主图 URL */
    private String mainImage;

    /** 轮播图集 URL JSON 数组 */
    private String imagesJson;

    /** 商品详情 JSON */
    private String detailJson;

    /** SPU 属性键值 JSON */
    private String attrsJson;

    /** 商品状态：0 草稿 1 待审核 2 审核拒绝 3 已上架 4 已下架 5 售罄 6 违规下架 7 已删除 */
    private Integer status;

    /** 审核备注 / 违规原因 */
    private String auditRemark;

    /** 审核人 ID */
    private Long auditorId;

    /** 审核时间 */
    private LocalDateTime auditTime;

    /** 最近上架时间 */
    private LocalDateTime onSaleTime;

    /** 累计销量 */
    private Long sales;

    /** 好评数 */
    private Long goodCommentCount;

    /** 总评价数 */
    private Long totalCommentCount;

    /** 好评率 */
    private BigDecimal goodRate;

    /** 乐观锁版本 */
    @Version
    private Integer version;
}
