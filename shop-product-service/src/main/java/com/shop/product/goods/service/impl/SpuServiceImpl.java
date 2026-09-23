package com.shop.product.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.dto.SpuDTO;
import com.shop.api.product.enums.GoodsStatuses;
import com.shop.api.product.enums.StockTypes;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.common.util.JsonUtils;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.product.category.entity.ProductCategory;
import com.shop.product.category.mapper.ProductCategoryMapper;
import com.shop.product.category.service.AttrKeyService;
import com.shop.product.category.service.SpuCategoryMountService;
import com.shop.product.goods.dto.SkuSaveRequest;
import com.shop.product.goods.dto.SpuAuditRequest;
import com.shop.product.goods.dto.SpuBrowseQuery;
import com.shop.product.goods.dto.SpuDetailVO;
import com.shop.product.goods.dto.SpuManageQuery;
import com.shop.product.goods.dto.SpuSaveRequest;
import com.shop.product.goods.dto.SpuSaveResult;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.entity.ProductSpu;
import com.shop.product.goods.mapper.ProductSkuMapper;
import com.shop.product.goods.mapper.ProductSpuMapper;
import com.shop.product.goods.service.SpuService;
import com.shop.product.goods.statemachine.GoodsStateMachine;
import com.shop.product.goods.support.GoodsDtoAssembler;
import com.shop.product.goods.support.SpuDetailCache;
import com.shop.product.support.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SPU/SKU 服务实现。状态流转统一走 {@link GoodsStateMachine} + 条件更新，
 * 每次流转同步 SKU 冗余状态并删除详情缓存。
 */
@Service
@RequiredArgsConstructor
public class SpuServiceImpl implements SpuService {

    /** C 端可见状态：已上架、售罄（design 3.2） */
    private static final Set<Integer> VISIBLE_STATUS =
            Set.of(GoodsStatuses.ON_SALE.getCode(), GoodsStatuses.SOLD_OUT.getCode());
    /** 允许编辑的状态：草稿、审核拒绝 */
    private static final Set<Integer> EDITABLE_STATUS =
            Set.of(GoodsStatuses.DRAFT.getCode(), GoodsStatuses.AUDIT_REJECT.getCode());

    private final ProductSpuMapper spuMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductCategoryMapper categoryMapper;
    private final GoodsStateMachine stateMachine;
    private final SpuDetailCache spuDetailCache;
    private final SpuCategoryMountService spuCategoryMountService;
    private final AttrKeyService attrKeyService;

    // ------------------------------------------------------------------
    // 商户 CRUD
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpuSaveResult create(SpuSaveRequest request) {
        long merchantId = AuthUtils.requireMerchantId();
        checkCategory3(request.getCategory3Id());

        ProductSpu spu = new ProductSpu();
        spu.setMerchantId(merchantId);
        spu.setShopId(request.getShopId());
        spu.setName(request.getName());
        spu.setBrandId(request.getBrandId());
        spu.setCategory3Id(request.getCategory3Id());
        spu.setMainImage(request.getMainImage());
        spu.setImagesJson(JsonUtils.toJson(safeList(request.getImages())));
        spu.setDetailJson(request.getDetailJson());
        spu.setAttrsJson(request.getAttrsJson());
        spu.setStatus(GoodsStatuses.DRAFT.getCode());
        spu.setSales(0L);
        spu.setGoodCommentCount(0L);
        spu.setTotalCommentCount(0L);
        spu.setGoodRate(BigDecimal.ZERO);
        spuMapper.insert(spu);

        for (SkuSaveRequest skuRequest : request.getSkus()) {
            ProductSku sku = buildNewSku(spu, skuRequest);
            try {
                skuMapper.insert(sku);
            } catch (DuplicateKeyException e) {
                throw new BizException(ErrorCode.CONFLICT, "SKU 编码已存在：" + skuRequest.getSkuCode(), e);
            }
        }
        // B13：虚拟类目多挂载（null=老端不传则不改动，此处新建即无挂载）
        spuCategoryMountService.syncVirtualMounts(spu.getId(), request.getCategoryIds());
        // B13：attrsJson 按属性主数据校验，仅告警不阻断（原样进快照，向后兼容）
        List<String> warnings = attrKeyService.validateAttrs(
                request.getCategory3Id(), request.getAttrsJson());
        return SpuSaveResult.of(spu.getId(), warnings);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpuSaveResult update(Long spuId, SpuSaveRequest request) {
        ProductSpu spu = requireSpu(spuId);
        AuthUtils.checkOwner(spu.getMerchantId());
        if (!EDITABLE_STATUS.contains(spu.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "仅草稿/审核拒绝状态的商品可以编辑");
        }
        checkCategory3(request.getCategory3Id());

        spu.setShopId(request.getShopId());
        spu.setName(request.getName());
        spu.setBrandId(request.getBrandId());
        spu.setCategory3Id(request.getCategory3Id());
        spu.setMainImage(request.getMainImage());
        spu.setImagesJson(JsonUtils.toJson(safeList(request.getImages())));
        spu.setDetailJson(request.getDetailJson());
        spu.setAttrsJson(request.getAttrsJson());
        spuMapper.updateById(spu);

        List<ProductSku> existing = skuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getSpuId, spuId));
        List<Long> keepIds = new ArrayList<>();
        for (SkuSaveRequest skuRequest : request.getSkus()) {
            if (skuRequest.getSkuId() == null) {
                ProductSku sku = buildNewSku(spu, skuRequest);
                try {
                    skuMapper.insert(sku);
                } catch (DuplicateKeyException e) {
                    throw new BizException(ErrorCode.CONFLICT, "SKU 编码已存在：" + skuRequest.getSkuCode(), e);
                }
            } else {
                ProductSku sku = existing.stream()
                        .filter(s -> s.getId().equals(skuRequest.getSkuId()))
                        .findFirst()
                        .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID,
                                "SKU 不属于该 SPU：" + skuRequest.getSkuId()));
                keepIds.add(sku.getId());
                mergeSku(spu, sku, skuRequest);
                if (skuRequest.getStockQty() != null
                        && nz(sku.getLockedStock()) + nz(sku.getOccupiedStock()) == 0) {
                    sku.setAvailableStock(skuRequest.getStockQty());
                }
                skuMapper.updateById(sku);
            }
        }
        for (ProductSku sku : existing) {
            if (!keepIds.contains(sku.getId())) {
                if (nz(sku.getLockedStock()) + nz(sku.getOccupiedStock()) > 0) {
                    throw new BizException(ErrorCode.CONFLICT,
                            "SKU[" + sku.getSkuCode() + "] 存在锁定/占用库存，不能删除");
                }
                // deleted 置为行 id：同 sku_code 删除后可再次创建（uk_sku_code 含 deleted）
                skuMapper.update(null, new LambdaUpdateWrapper<ProductSku>()
                        .eq(ProductSku::getId, sku.getId())
                        .setSql("deleted = id"));
            }
        }
        // B13：按上送集合整批替换虚拟挂载（null 保持不变）
        spuCategoryMountService.syncVirtualMounts(spuId, request.getCategoryIds());
        List<String> warnings = attrKeyService.validateAttrs(
                request.getCategory3Id(), request.getAttrsJson());
        spuDetailCache.evict(spuId);
        return SpuSaveResult.of(spuId, warnings);
    }

    private ProductSku buildNewSku(ProductSpu spu, SkuSaveRequest r) {
        ProductSku sku = new ProductSku();
        sku.setSpuId(spu.getId());
        sku.setMerchantId(spu.getMerchantId());
        sku.setShopId(spu.getShopId());
        sku.setCategory3Id(spu.getCategory3Id());
        sku.setStatus(spu.getStatus());
        sku.setAvailableStock(r.getStockQty() == null ? 0L : r.getStockQty());
        sku.setLockedStock(0L);
        sku.setOccupiedStock(0L);
        sku.setDefectStock(0L);
        sku.setWarnThreshold(10L);
        mergeSku(spu, sku, r);
        return sku;
    }

    /** 填充可编辑字段（新建/更新共用，库存数量除外）。 */
    private void mergeSku(ProductSpu spu, ProductSku sku, SkuSaveRequest r) {
        sku.setSkuCode(r.getSkuCode());
        sku.setSkuName(StringUtils.hasText(r.getSkuName()) ? r.getSkuName() : spu.getName());
        sku.setSpecText(r.getSpecText() == null ? "" : r.getSpecText());
        sku.setImage(r.getImage());
        sku.setBarcode(r.getBarcode());
        sku.setWeightGram(r.getWeightGram() == null ? 0 : r.getWeightGram());
        sku.setVolumeCc(r.getVolumeCc() == null ? 0 : r.getVolumeCc());
        sku.setMarketPriceFen(r.getMarketPriceFen());
        sku.setSalePriceFen(r.getSalePriceFen());
        sku.setMemberPriceFen(r.getMemberPriceFen());
        sku.setPromotionPriceFen(r.getPromotionPriceFen());
        sku.setSeckillPriceFen(r.getSeckillPriceFen());
        sku.setCostPriceFen(r.getCostPriceFen());
        if (r.getWarnThreshold() != null) {
            sku.setWarnThreshold(r.getWarnThreshold());
        }
        sku.setPresaleFlag(r.getPresaleFlag() == null ? 0 : r.getPresaleFlag());
        sku.setStockType(r.getStockType() == null ? StockTypes.NORMAL.getCode() : r.getStockType());
    }

    // ------------------------------------------------------------------
    // 状态流转
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitAudit(Long spuId) {
        ProductSpu spu = requireSpu(spuId);
        AuthUtils.checkOwner(spu.getMerchantId());
        transit(spu, GoodsStatuses.PENDING_AUDIT.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long spuId, SpuAuditRequest request) {
        LoginUser operator = AuthUtils.requirePlatform();
        ProductSpu spu = requireSpu(spuId);
        int target = Boolean.TRUE.equals(request.getPass())
                ? GoodsStatuses.ON_SALE.getCode() : GoodsStatuses.AUDIT_REJECT.getCode();
        transit(spu, target);
        LambdaUpdateWrapper<ProductSpu> update = new LambdaUpdateWrapper<ProductSpu>()
                .eq(ProductSpu::getId, spuId)
                .set(ProductSpu::getAuditorId, operator.getUserId())
                .set(ProductSpu::getAuditTime, LocalDateTime.now())
                .set(ProductSpu::getAuditRemark, request.getRemark());
        if (Boolean.TRUE.equals(request.getPass())) {
            update.set(ProductSpu::getOnSaleTime, LocalDateTime.now());
        }
        spuMapper.update(null, update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onSale(Long spuId) {
        ProductSpu spu = requireSpu(spuId);
        AuthUtils.checkOwner(spu.getMerchantId());
        transit(spu, GoodsStatuses.ON_SALE.getCode());
        spuMapper.update(null, new LambdaUpdateWrapper<ProductSpu>()
                .eq(ProductSpu::getId, spuId)
                .set(ProductSpu::getOnSaleTime, LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void offSale(Long spuId) {
        ProductSpu spu = requireSpu(spuId);
        AuthUtils.checkOwner(spu.getMerchantId());
        transit(spu, GoodsStatuses.OFF_SALE.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void violationOff(Long spuId, String remark) {
        LoginUser operator = AuthUtils.requirePlatform();
        ProductSpu spu = requireSpu(spuId);
        transit(spu, GoodsStatuses.VIOLATION_OFF.getCode());
        spuMapper.update(null, new LambdaUpdateWrapper<ProductSpu>()
                .eq(ProductSpu::getId, spuId)
                .set(ProductSpu::getAuditorId, operator.getUserId())
                .set(ProductSpu::getAuditTime, LocalDateTime.now())
                .set(ProductSpu::getAuditRemark, StringUtils.hasText(remark) ? remark : "平台违规下架"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long spuId) {
        ProductSpu spu = requireSpu(spuId);
        AuthUtils.checkOwner(spu.getMerchantId());
        List<ProductSku> skus = skuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getSpuId, spuId));
        for (ProductSku sku : skus) {
            if (nz(sku.getLockedStock()) + nz(sku.getOccupiedStock()) > 0) {
                throw new BizException(ErrorCode.CONFLICT,
                        "SKU[" + sku.getSkuCode() + "] 存在未完成订单库存，不能删除商品");
            }
        }
        transit(spu, GoodsStatuses.DELETED.getCode());
        // 状态置 7 后逻辑删除 SPU/SKU 行
        spuMapper.deleteById(spuId);
        // SKU 删除值写入各行 id，避免同 sku_code 历史删除行撞 uk_sku_code
        skuMapper.update(null, new LambdaUpdateWrapper<ProductSku>()
                .eq(ProductSku::getSpuId, spuId)
                .setSql("deleted = id"));
    }

    /**
     * 统一状态流转：状态机校验 → 条件更新（0 行即并发冲突）→ 同步 SKU 状态 → 删缓存。
     */
    private void transit(ProductSpu spu, int target) {
        int from = spu.getStatus();
        stateMachine.checkTransition(from, target);
        int rows = spuMapper.updateStatusIf(spu.getId(), from, target);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "商品状态已变更，请刷新后重试");
        }
        spu.setStatus(target);
        skuMapper.update(null, new LambdaUpdateWrapper<ProductSku>()
                .eq(ProductSku::getSpuId, spu.getId())
                .set(ProductSku::getStatus, target));
        spuDetailCache.evict(spu.getId());
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public PageResult<SpuDTO> managePage(SpuManageQuery query) {
        long merchantId = AuthUtils.requireMerchantId();
        Page<ProductSpu> page = new Page<>(query.safePageNum(), query.safePageSize());
        Page<ProductSpu> result = spuMapper.selectPage(page,
                new LambdaQueryWrapper<ProductSpu>()
                        .eq(ProductSpu::getMerchantId, merchantId)
                        .eq(query.getStatus() != null, ProductSpu::getStatus, query.getStatus())
                        .eq(query.getCategory3Id() != null, ProductSpu::getCategory3Id, query.getCategory3Id())
                        .like(StringUtils.hasText(query.getKeyword()), ProductSpu::getName, query.getKeyword())
                        .orderByDesc(ProductSpu::getId));
        List<SpuDTO> list = result.getRecords().stream().map(this::toSpuDTOWithImages).toList();
        return PageResult.of(query.safePageNum(), query.safePageSize(), result.getTotal(), list);
    }

    @Override
    public PageResult<SpuDTO> adminPage(SpuManageQuery query) {
        AuthUtils.requirePlatform();
        Page<ProductSpu> page = new Page<>(query.safePageNum(), query.safePageSize());
        Page<ProductSpu> result = spuMapper.selectPage(page,
                new LambdaQueryWrapper<ProductSpu>()
                        .eq(query.getStatus() != null, ProductSpu::getStatus, query.getStatus())
                        .eq(query.getCategory3Id() != null, ProductSpu::getCategory3Id, query.getCategory3Id())
                        .like(StringUtils.hasText(query.getKeyword()), ProductSpu::getName, query.getKeyword())
                        .orderByDesc(ProductSpu::getId));
        List<SpuDTO> list = result.getRecords().stream().map(this::toSpuDTOWithImages).toList();
        return PageResult.of(query.safePageNum(), query.safePageSize(), result.getTotal(), list);
    }

    @Override
    public SpuDetailVO manageDetail(Long spuId) {
        ProductSpu spu = requireSpu(spuId);
        LoginUser user = UserContext.get();
        if (user.getUserType() == null || user.getUserType() != AuthUtils.USER_TYPE_PLATFORM) {
            AuthUtils.checkOwner(spu.getMerchantId());
        }
        return buildDetail(spu);
    }

    @Override
    public SpuDetailVO browseDetail(Long spuId) {
        ProductSpu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        if (!VISIBLE_STATUS.contains(spu.getStatus())) {
            throw new BizException(ErrorCode.GOODS_NOT_SALE, "商品不可售或已下架");
        }
        long version = spu.getVersion() == null ? 0L : spu.getVersion();
        SpuDetailVO cached = spuDetailCache.get(spuId, version);
        if (cached != null) {
            return cached;
        }
        SpuDetailVO detail = buildDetail(spu);
        spuDetailCache.put(spuId, version, detail);
        return detail;
    }

    @Override
    public PageResult<SpuDTO> browsePage(SpuBrowseQuery query) {
        Page<ProductSpu> page = new Page<>(query.safePageNum(), query.safePageSize());
        Long categoryId = query.getCategory3Id();
        // B13：按任一挂载类目可查到 SPU —— 主归属 category3_id 命中 或 存在虚拟挂载关系
        String mountSubQuery = categoryId == null ? null
                : "SELECT spu_id FROM t_product_spu_category WHERE category_id = " + categoryId
                        + " AND deleted = 0";
        Page<ProductSpu> result = spuMapper.selectPage(page,
                new LambdaQueryWrapper<ProductSpu>()
                        .eq(ProductSpu::getStatus, GoodsStatuses.ON_SALE.getCode())
                        .and(categoryId != null, w -> w
                                .eq(ProductSpu::getCategory3Id, categoryId)
                                .or().inSql(ProductSpu::getId, mountSubQuery))
                        .like(StringUtils.hasText(query.getKeyword()), ProductSpu::getName, query.getKeyword())
                        .orderByDesc(ProductSpu::getSales)
                        .orderByDesc(ProductSpu::getId));
        List<SpuDTO> list = result.getRecords().stream().map(this::toSpuDTOWithImages).toList();
        return PageResult.of(query.safePageNum(), query.safePageSize(), result.getTotal(), list);
    }

    private SpuDetailVO buildDetail(ProductSpu spu) {
        SpuDetailVO vo = new SpuDetailVO();
        vo.setSpu(toSpuDTOWithImages(spu));
        vo.setDetailJson(spu.getDetailJson());
        vo.setAttrsJson(spu.getAttrsJson());
        List<ProductSku> skus = skuMapper.selectList(new LambdaQueryWrapper<ProductSku>()
                .eq(ProductSku::getSpuId, spu.getId())
                .orderByAsc(ProductSku::getId));
        List<SkuDTO> skuDTOs = skus.stream().map(GoodsDtoAssembler::toSkuDTO).toList();
        skuDTOs.forEach(dto -> dto.setSpuName(spu.getName()));
        vo.setSkus(skuDTOs);
        return vo;
    }

    private SpuDTO toSpuDTOWithImages(ProductSpu spu) {
        SpuDTO dto = GoodsDtoAssembler.toSpuDTO(spu);
        dto.setImages(parseImages(spu.getImagesJson()));
        return dto;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseImages(String imagesJson) {
        if (!StringUtils.hasText(imagesJson)) {
            return new ArrayList<>();
        }
        List<String> images = JsonUtils.fromJson(imagesJson, List.class);
        return images == null ? new ArrayList<>() : (List<String>) images;
    }

    private List<String> safeList(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }

    private void checkCategory3(Long category3Id) {
        ProductCategory category = categoryMapper.selectById(category3Id);
        if (category == null || category.getStatus() == null || category.getStatus() != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "三级类目不存在或已停用");
        }
        if (category.getLevel() == null || category.getLevel() != 3) {
            throw new BizException(ErrorCode.PARAM_INVALID, "商品必须挂在三级类目下");
        }
    }

    private ProductSpu requireSpu(Long spuId) {
        ProductSpu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return spu;
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }
}
