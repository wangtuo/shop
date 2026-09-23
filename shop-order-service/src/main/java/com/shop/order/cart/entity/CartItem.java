package com.shop.order.cart.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 购物车条目（t_order_cart，user_id + sku_id + deleted 唯一）。
 * <p>deleted 语义：0=未删除；逻辑删除时写入该行雪花 id（各历史行互不相同），
 * 故删除后可重新加购同一 SKU。删除语句须显式 {@code setSql("deleted = id")}，
 * 不走 BaseEntity 默认的 deleteById（那会置 deleted=1 撞唯一键）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order_cart")
public class CartItem extends BaseEntity {

    /** 用户 ID */
    private Long userId;
    /** SKU ID */
    private Long skuId;
    /** SPU ID */
    private Long spuId;
    /** 商户 ID */
    private Long merchantId;
    /** 店铺 ID */
    private Long shopId;
    /** SKU 名称快照 */
    private String skuName;
    /** 规格文本快照 */
    private String specText;
    /** 商品主图快照 */
    private String image;
    /** 加购时单价（分） */
    private Long priceFen;
    /** 购买数量 */
    private Integer qty;
    /** 是否勾选：0 否 1 是 */
    private Integer selected;
    /** 是否失效：0 有效 1 失效 */
    private Integer invalid;
    /** 失效原因：0 未失效 1 已下架 2 售罄/库存 0 3 已删除 */
    private Integer invalidReason;

    @Version
    private Integer version;
}
