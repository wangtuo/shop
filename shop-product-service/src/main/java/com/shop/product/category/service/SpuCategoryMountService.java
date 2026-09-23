package com.shop.product.category.service;

import com.shop.product.category.entity.ProductSpuCategory;

import java.util.List;

/**
 * SPU-类目多挂载服务（B13）：主归属由 t_product_spu.category3_id 表达且不可卸载，
 * 本服务只处理虚拟挂载（mount_type=2）。
 */
public interface SpuCategoryMountService {

    /** 查 SPU 的全部挂载关系（不含 SPU 主归属）。 */
    List<ProductSpuCategory> listMounts(Long spuId);

    /**
     * 虚拟挂载 SPU 到类目（幂等：重复挂载成功）。类目必须存在且启用；
     * SPU 主归属类目（category3_id）拒绝重复挂载。
     */
    void mount(Long spuId, Long categoryId);

    /**
     * 卸载虚拟挂载。SPU 主归属 category3_id 不可卸载；挂载不存在按幂等成功。
     */
    void unmount(Long spuId, Long categoryId);

    /**
     * 建品/编辑保存时整批同步虚拟挂载（mount_type=2）。
     *
     * @param requestedCategories 请求的挂载类目；null 表示不改动既有挂载；非 null（含空集）
     *                            表示替换为该集合（SPU 主归属类目自动剔除）
     */
    void syncVirtualMounts(Long spuId, List<Long> requestedCategories);
}
