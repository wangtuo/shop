package com.shop.product.category.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.product.category.entity.ProductCategory;
import com.shop.product.category.entity.ProductSpuCategory;
import com.shop.product.category.mapper.ProductCategoryMapper;
import com.shop.product.category.mapper.ProductSpuCategoryMapper;
import com.shop.product.category.service.SpuCategoryMountService;
import com.shop.product.goods.entity.ProductSpu;
import com.shop.product.goods.mapper.ProductSpuMapper;
import com.shop.product.support.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SPU-类目虚拟挂载实现（B13）。挂载/卸载强制商户归属校验；
 * 主归属 category3_id 不可卸载；uk(spu_id, category_id) 幂等。
 */
@Service
@RequiredArgsConstructor
public class SpuCategoryMountServiceImpl implements SpuCategoryMountService {

    /** 虚拟挂载类型 */
    private static final int MOUNT_TYPE_VIRTUAL = 2;

    private final ProductSpuMapper spuMapper;
    private final ProductCategoryMapper categoryMapper;
    private final ProductSpuCategoryMapper mountMapper;

    @Override
    public List<ProductSpuCategory> listMounts(Long spuId) {
        requireOwnedSpu(spuId);
        return mountMapper.selectBySpu(spuId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void mount(Long spuId, Long categoryId) {
        ProductSpu spu = requireOwnedSpu(spuId);
        if (categoryId.equals(spu.getCategory3Id())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "主归属类目无需重复虚拟挂载：" + categoryId);
        }
        ProductCategory category = requireEnabledCategory(categoryId);
        try {
            ProductSpuCategory mount = new ProductSpuCategory();
            mount.setSpuId(spuId);
            mount.setCategoryId(categoryId);
            mount.setCategoryLevel(category.getLevel());
            mount.setMountType(MOUNT_TYPE_VIRTUAL);
            mountMapper.insert(mount);
        } catch (DuplicateKeyException e) {
            // uk(spu_id, category_id)：重复挂载幂等成功
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unmount(Long spuId, Long categoryId) {
        ProductSpu spu = requireOwnedSpu(spuId);
        if (categoryId.equals(spu.getCategory3Id())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "主归属类目不可卸载：" + categoryId);
        }
        // 不存在按幂等成功（deleted=id 写法保证后续可重新挂载）
        mountMapper.deleteOne(spuId, categoryId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncVirtualMounts(Long spuId, List<Long> requestedCategories) {
        if (requestedCategories == null) {
            // 未上送 categoryIds：保持既有挂载不变（向后兼容老端）
            return;
        }
        ProductSpu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在：" + spuId);
        }
        Set<Long> target = new LinkedHashSet<>(requestedCategories);
        target.removeIf(id -> id == null || id.equals(spu.getCategory3Id()));
        if (target.size() > 20) {
            throw new BizException(ErrorCode.PARAM_INVALID, "虚拟挂载类目最多 20 个");
        }
        for (Long categoryId : target) {
            requireEnabledCategory(categoryId);
        }
        // 全量替换 mount_type=2（主归属 mount_type=1 不经本表维护，不会被误删）
        mountMapper.deleteVirtualBySpu(spuId);
        for (Long categoryId : target) {
            ProductCategory category = categoryMapper.selectById(categoryId);
            ProductSpuCategory mount = new ProductSpuCategory();
            mount.setSpuId(spuId);
            mount.setCategoryId(categoryId);
            mount.setCategoryLevel(category.getLevel());
            mount.setMountType(MOUNT_TYPE_VIRTUAL);
            try {
                mountMapper.insert(mount);
            } catch (DuplicateKeyException e) {
                // 删除行残留 uk 冲突的极端并发下幂等跳过
            }
        }
    }

    private ProductSpu requireOwnedSpu(Long spuId) {
        ProductSpu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在：" + spuId);
        }
        AuthUtils.checkOwner(spu.getMerchantId());
        return spu;
    }

    private ProductCategory requireEnabledCategory(Long categoryId) {
        ProductCategory category = categoryMapper.selectById(categoryId);
        if (category == null || category.getStatus() == null || category.getStatus() != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "挂载类目不存在或已停用：" + categoryId);
        }
        return category;
    }
}
