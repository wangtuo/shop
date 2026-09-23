package com.shop.product.goods.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.product.goods.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * SKU Mapper：库存 TCC 全部走条件更新防超卖（CONTRACTS.md §3.3）。
 */
@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    /**
     * TCC-try：可售 → 锁定。条件 available_stock >= qty，影响 0 行即库存不足。
     */
    @Update("UPDATE t_product_sku SET available_stock = available_stock - #{qty}, "
            + "locked_stock = locked_stock + #{qty}, version = version + 1, update_time = NOW() "
            + "WHERE id = #{skuId} AND deleted = 0 AND available_stock >= #{qty}")
    int lockStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * TCC-confirm：锁定 → 占用。
     */
    @Update("UPDATE t_product_sku SET locked_stock = locked_stock - #{qty}, "
            + "occupied_stock = occupied_stock + #{qty}, version = version + 1, update_time = NOW() "
            + "WHERE id = #{skuId} AND deleted = 0 AND locked_stock >= #{qty}")
    int confirmDeduct(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * TCC-cancel：锁定 → 可售。
     */
    @Update("UPDATE t_product_sku SET locked_stock = locked_stock - #{qty}, "
            + "available_stock = available_stock + #{qty}, version = version + 1, update_time = NOW() "
            + "WHERE id = #{skuId} AND deleted = 0 AND locked_stock >= #{qty}")
    int releaseStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * 售后回可售库：占用 → 可售（买家责任 / 换货）。
     */
    @Update("UPDATE t_product_sku SET occupied_stock = occupied_stock - #{qty}, "
            + "available_stock = available_stock + #{qty}, version = version + 1, update_time = NOW() "
            + "WHERE id = #{skuId} AND deleted = 0 AND occupied_stock >= #{qty}")
    int returnToAvailable(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * 质量问题退货：占用 → 残次仓（不可售）。
     */
    @Update("UPDATE t_product_sku SET occupied_stock = occupied_stock - #{qty}, "
            + "defect_stock = defect_stock + #{qty}, version = version + 1, update_time = NOW() "
            + "WHERE id = #{skuId} AND deleted = 0 AND occupied_stock >= #{qty}")
    int returnToDefect(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * B5 预售定金支付：预售池 → 占用。条件 presale_stock >= qty，影响 0 行即预售库存不足
     * （事件可重试，等待运营补货后成立）。
     */
    @Update("UPDATE t_product_sku SET presale_stock = presale_stock - #{qty}, "
            + "occupied_stock = occupied_stock + #{qty}, version = version + 1, update_time = NOW() "
            + "WHERE id = #{skuId} AND deleted = 0 AND presale_stock >= #{qty}")
    int deductPresaleStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * B5 预售尾款违约回补：占用 → 预售池（定金扣减的逆运算），带 occupied 行级守卫。
     */
    @Update("UPDATE t_product_sku SET occupied_stock = occupied_stock - #{qty}, "
            + "presale_stock = presale_stock + #{qty}, version = version + 1, update_time = NOW() "
            + "WHERE id = #{skuId} AND deleted = 0 AND occupied_stock >= #{qty}")
    int returnPresaleStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * B13 发货出账：占用仓出账（实物出仓由 WMS，本系统只记账）。条件 occupied_stock >= qty。
     */
    @Update("UPDATE t_product_sku SET occupied_stock = occupied_stock - #{qty}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE id = #{skuId} AND deleted = 0 AND occupied_stock >= #{qty}")
    int shipOutStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * B13 已出账订单退货回可售（买家责任/换货）：货已物理出仓，故只回可售、不再动占用。
     */
    @Update("UPDATE t_product_sku SET available_stock = available_stock + #{qty}, "
            + "version = version + 1, update_time = NOW() WHERE id = #{skuId} AND deleted = 0")
    int returnShippedToAvailable(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * B13 已出账订单退货入残次（商家责任质量问题）：只入残次仓、不再动占用。
     */
    @Update("UPDATE t_product_sku SET defect_stock = defect_stock + #{qty}, "
            + "version = version + 1, update_time = NOW() WHERE id = #{skuId} AND deleted = 0")
    int returnShippedToDefect(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * 商户补货：可售库存正向增加（售罄自动上架由此驱动）。
     */
    @Update("UPDATE t_product_sku SET available_stock = available_stock + #{qty}, "
            + "version = version + 1, update_time = NOW() WHERE id = #{skuId} AND deleted = 0")
    int replenish(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * 统计同一 SPU 下除指定 SKU 外的可售库存总和，用于判断 SPU 是否整体售罄。
     */
    @Select("SELECT COALESCE(SUM(available_stock), 0) FROM t_product_sku "
            + "WHERE spu_id = #{spuId} AND deleted = 0 AND id <> #{excludeSkuId}")
    Long sumAvailableExclude(@Param("spuId") Long spuId, @Param("excludeSkuId") Long excludeSkuId);

    /**
     * R4-25 低库存预警武装抢占：仅当尚未预警（low_stock_alerted=0）时置 1。
     * @return 1 本事务赢得本轮预警权；0 并发他事务/上一轮已发，调用方静默跳过
     */
    @Update("UPDATE t_product_sku SET low_stock_alerted = 1, update_time = NOW() "
            + "WHERE id = #{skuId} AND deleted = 0 AND low_stock_alerted = 0")
    int casLowStockAlertOn(@Param("skuId") Long skuId);

    /**
     * R4-25 可售恢复到阈值以上时重新武装：仅当已预警（=1）时复位 0，
     * 使下一次跌破阈值能再发一轮预警。
     */
    @Update("UPDATE t_product_sku SET low_stock_alerted = 0, update_time = NOW() "
            + "WHERE id = #{skuId} AND deleted = 0 AND low_stock_alerted = 1")
    int casLowStockAlertOff(@Param("skuId") Long skuId);
}
