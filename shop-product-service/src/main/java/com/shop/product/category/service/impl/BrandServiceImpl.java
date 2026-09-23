package com.shop.product.category.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.product.category.dto.BrandSaveRequest;
import com.shop.product.category.entity.ProductBrand;
import com.shop.product.category.mapper.ProductBrandMapper;
import com.shop.product.category.service.BrandService;
import com.shop.product.support.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 品牌服务实现。
 */
@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    private final ProductBrandMapper brandMapper;

    @Override
    public PageResult<ProductBrand> page(Integer pageNum, Integer pageSize, String keyword, String initial) {
        int pn = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int ps = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 200);
        LambdaQueryWrapper<ProductBrand> wrapper = new LambdaQueryWrapper<ProductBrand>()
                .like(StringUtils.hasText(keyword), ProductBrand::getName, keyword)
                .eq(StringUtils.hasText(initial), ProductBrand::getInitial, initial)
                .orderByAsc(ProductBrand::getSort)
                .orderByDesc(ProductBrand::getId);
        Page<ProductBrand> page = brandMapper.selectPage(new Page<>(pn, ps), wrapper);
        return PageResult.of(pn, ps, page.getTotal(), page.getRecords());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(BrandSaveRequest request) {
        AuthUtils.requirePlatform();
        ProductBrand brand = new ProductBrand();
        brand.setName(request.getName());
        brand.setLogo(request.getLogo());
        brand.setInitial(request.getInitial());
        brand.setSort(request.getSort() == null ? 0 : request.getSort());
        brand.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        brandMapper.insert(brand);
        return brand.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BrandSaveRequest request) {
        AuthUtils.requirePlatform();
        ProductBrand brand = requireBrand(id);
        brand.setName(request.getName());
        brand.setLogo(request.getLogo());
        brand.setInitial(request.getInitial());
        if (request.getSort() != null) {
            brand.setSort(request.getSort());
        }
        if (request.getStatus() != null) {
            brand.setStatus(request.getStatus());
        }
        brandMapper.updateById(brand);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AuthUtils.requirePlatform();
        requireBrand(id);
        // deleted 置为行 id（非固定 1），同名品牌删除后可再次新建（uk_name 含 deleted）
        brandMapper.update(null, new LambdaUpdateWrapper<ProductBrand>()
                .eq(ProductBrand::getId, id)
                .setSql("deleted = id"));
    }

    private ProductBrand requireBrand(Long id) {
        ProductBrand brand = brandMapper.selectById(id);
        if (brand == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "品牌不存在");
        }
        return brand;
    }
}
