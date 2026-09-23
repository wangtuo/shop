package com.shop.marketing.promo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageQuery;
import com.shop.common.result.PageResult;
import com.shop.marketing.promo.dto.PromoSaveRequest;
import com.shop.marketing.promo.entity.Promo;
import com.shop.marketing.promo.entity.PromoLevel;
import com.shop.marketing.promo.entity.PromoTarget;
import com.shop.marketing.promo.mapper.PromoLevelMapper;
import com.shop.marketing.promo.mapper.PromoMapper;
import com.shop.marketing.promo.mapper.PromoTargetMapper;
import com.shop.marketing.support.MarketingAuditStatus;
import com.shop.marketing.support.MarketingStatusMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 商户/平台促销管理：CRUD + 上下架 + 分页。 */
@Service
@RequiredArgsConstructor
public class PromoAdminService {

    private final PromoMapper promoMapper;
    private final PromoLevelMapper levelMapper;
    private final PromoTargetMapper targetMapper;

    @Transactional(rollbackFor = Exception.class)
    public Long save(PromoSaveRequest req) {
        if (!req.getEndTime().isAfter(req.getStartTime())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "促销结束时间必须晚于开始时间");
        }
        Promo promo = new Promo();
        promo.setId(req.getId());
        promo.setMerchantId(req.getMerchantId());
        promo.setShopId(req.getShopId());
        promo.setName(req.getName());
        promo.setType(req.getType());
        promo.setScopeType(req.getScopeType());
        promo.setStartTime(req.getStartTime());
        promo.setEndTime(req.getEndTime());
        promo.setRemark(req.getRemark());
        if (req.getId() == null) {
            promo.setStatus(0);
            // 平台管理员后台直建：自动审核通过，保持存量行为不回退（B2）
            promo.setAuditStatus(MarketingAuditStatus.APPROVED.getCode());
            promoMapper.insert(promo);
        } else {
            if (promoMapper.updateById(promo) == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, "促销不存在");
            }
            levelMapper.delete(new LambdaQueryWrapper<PromoLevel>().eq(PromoLevel::getPromoId, req.getId()));
            targetMapper.delete(new LambdaQueryWrapper<PromoTarget>().eq(PromoTarget::getPromoId, req.getId()));
        }
        for (PromoSaveRequest.Level lv : req.getLevels()) {
            PromoLevel level = new PromoLevel();
            level.setPromoId(promo.getId());
            level.setThresholdFen(lv.getThresholdFen() == null ? 0L : lv.getThresholdFen());
            level.setReduceFen(lv.getReduceFen() == null ? 0L : lv.getReduceFen());
            level.setDiscountBp(lv.getDiscountBp() == null ? 1000 : lv.getDiscountBp());
            level.setNthIndex(lv.getNthIndex() == null ? 0 : lv.getNthIndex());
            level.setGiftSkuId(lv.getGiftSkuId());
            level.setGiftQty(lv.getGiftQty() == null ? 0 : lv.getGiftQty());
            levelMapper.insert(level);
        }
        for (PromoSaveRequest.Target t : req.getTargets()) {
            PromoTarget target = new PromoTarget();
            target.setPromoId(promo.getId());
            target.setTargetType(t.getTargetType());
            target.setTargetId(t.getTargetId());
            targetMapper.insert(target);
        }
        return promo.getId();
    }

    /**
     * 业务状态变更（API-M + B2）：0停用/1启用。
     * 目标态白名单与迁移合法性统一由 {@link MarketingStatusMachine} 校验；
     * 审核未通过（audit_status≠2）禁止启用；Mapper CAS 兜底并发。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer toStatus) {
        if (toStatus == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 不能为空");
        }
        Promo promo = promoMapper.selectById(id);
        if (promo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "促销不存在");
        }
        if (promo.getStatus() != null && promo.getStatus() == toStatus) {
            return;
        }
        MarketingStatusMachine.assertBizTransition(MarketingStatusMachine.Domain.PROMO,
                promo.getAuditStatus(), promo.getStatus() == null ? 0 : promo.getStatus(), toStatus);
        if (promoMapper.updateStatus(id, promo.getStatus(), toStatus) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "促销状态已变更，请刷新重试");
        }
    }

    public Promo detail(Long id) {
        Promo promo = promoMapper.selectById(id);
        if (promo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "促销不存在");
        }
        return promo;
    }

    public List<PromoLevel> levels(Long promoId) {
        return levelMapper.selectList(new LambdaQueryWrapper<PromoLevel>().eq(PromoLevel::getPromoId, promoId));
    }

    public List<PromoTarget> targets(Long promoId) {
        return targetMapper.selectList(new LambdaQueryWrapper<PromoTarget>().eq(PromoTarget::getPromoId, promoId));
    }

    public PageResult<Promo> page(long pageNum, long pageSize, Integer type, Integer status, Long shopId) {
        PageQuery pq = new PageQuery();
        pq.setPageNum((int) pageNum);
        pq.setPageSize((int) pageSize);
        int safeNum = pq.safePageNum();
        int safeSize = pq.safePageSize();
        LambdaQueryWrapper<Promo> wrapper = new LambdaQueryWrapper<Promo>()
                .eq(type != null, Promo::getType, type)
                .eq(status != null, Promo::getStatus, status)
                .eq(shopId != null, Promo::getShopId, shopId)
                .orderByDesc(Promo::getId);
        Page<Promo> page = promoMapper.selectPage(new Page<>(safeNum, safeSize), wrapper);
        return PageResult.of(safeNum, safeSize, page.getTotal(), page.getRecords() == null ? new ArrayList<>() : page.getRecords());
    }
}
