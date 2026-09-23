package com.shop.marketing.coupon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageQuery;
import com.shop.common.result.PageResult;
import com.shop.marketing.coupon.dto.CouponSaveRequest;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.entity.CouponTarget;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.coupon.mapper.CouponTargetMapper;
import com.shop.marketing.support.MarketingAuditStatus;
import com.shop.marketing.support.MarketingStatusMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 券模板管理：CRUD + 上下/作废 + 分页 + 新人礼包标记（卡 B2/API-M/B6）。 */
@Service
@RequiredArgsConstructor
public class CouponAdminService {

    private final CouponMapper couponMapper;
    private final CouponTargetMapper targetMapper;

    @Transactional(rollbackFor = Exception.class)
    public Long save(CouponSaveRequest req) {
        if (!req.getReceiveEndTime().isAfter(req.getReceiveStartTime())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "领取结束时间必须晚于开始时间");
        }
        boolean newUserGift = req.getNewUserGift() != null && req.getNewUserGift() == 1;
        // B6：新人礼包券发放方式必须为 3（NEW_USER）
        if (newUserGift && (req.getIssueWay() == null || req.getIssueWay() != 3)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新人礼包券发放方式必须为新人礼包(3)");
        }
        Coupon coupon = new Coupon();
        coupon.setId(req.getId());
        coupon.setMerchantId(req.getMerchantId() == null ? 0L : req.getMerchantId());
        coupon.setShopId(req.getShopId());
        coupon.setName(req.getName());
        coupon.setType(req.getType());
        coupon.setScopeType(req.getScopeType());
        coupon.setFaceValueFen(req.getFaceValueFen() == null ? 0L : req.getFaceValueFen());
        coupon.setThresholdFen(req.getThresholdFen() == null ? 0L : req.getThresholdFen());
        coupon.setDiscountBp(req.getDiscountBp() == null ? 1000 : req.getDiscountBp());
        coupon.setMaxDiscountFen(req.getMaxDiscountFen());
        coupon.setTotalCount(req.getTotalCount() == null ? 0 : req.getTotalCount());
        coupon.setPerUserLimit(req.getPerUserLimit() == null ? 1 : req.getPerUserLimit());
        coupon.setIssueWay(req.getIssueWay() == null ? 1 : req.getIssueWay());
        coupon.setValidType(req.getValidType());
        coupon.setValidDays(req.getValidDays() == null ? 0 : req.getValidDays());
        coupon.setValidStartTime(req.getValidStartTime());
        coupon.setValidEndTime(req.getValidEndTime());
        coupon.setReceiveStartTime(req.getReceiveStartTime());
        coupon.setReceiveEndTime(req.getReceiveEndTime());
        coupon.setNewUserGift(newUserGift ? 1 : 0);
        if (req.getId() == null) {
            coupon.setReceivedCount(0);
            coupon.setStatus(0);
            // 平台管理员后台直建：自动审核通过，保持存量行为不回退（B2）
            coupon.setAuditStatus(MarketingAuditStatus.APPROVED.getCode());
            couponMapper.insert(coupon);
        } else {
            if (couponMapper.updateById(coupon) == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, "优惠券不存在");
            }
            targetMapper.delete(new LambdaQueryWrapper<CouponTarget>().eq(CouponTarget::getCouponId, req.getId()));
        }
        for (CouponSaveRequest.Target t : req.getTargets()) {
            CouponTarget target = new CouponTarget();
            target.setCouponId(coupon.getId());
            target.setTargetType(t.getTargetType());
            target.setTargetId(t.getTargetId());
            targetMapper.insert(target);
        }
        return coupon.getId();
    }

    /**
     * 业务状态变更（API-M + B2）：0下架/1上架/2作废。
     * 目标态白名单与迁移合法性统一由 {@link MarketingStatusMachine} 校验；
     * 审核未通过（audit_status≠2）禁止上架；作废不可逆；Mapper CAS 兜底并发。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer toStatus) {
        if (toStatus == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 不能为空");
        }
        Coupon coupon = couponMapper.selectById(id);
        if (coupon == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券不存在");
        }
        if (coupon.getStatus() != null && coupon.getStatus() == toStatus) {
            return;
        }
        MarketingStatusMachine.assertBizTransition(MarketingStatusMachine.Domain.COUPON,
                coupon.getAuditStatus(), coupon.getStatus() == null ? 0 : coupon.getStatus(), toStatus);
        if (couponMapper.updateStatus(id, coupon.getStatus(), toStatus) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "券状态已变更，请刷新重试");
        }
    }

    public List<CouponTarget> targets(Long couponId) {
        return targetMapper.selectList(new LambdaQueryWrapper<CouponTarget>().eq(CouponTarget::getCouponId, couponId));
    }

    public PageResult<Coupon> page(long pageNum, long pageSize, Integer type, Integer status, Long shopId) {
        PageQuery pq = new PageQuery();
        pq.setPageNum((int) pageNum);
        pq.setPageSize((int) pageSize);
        int safeNum = pq.safePageNum();
        int safeSize = pq.safePageSize();
        LambdaQueryWrapper<Coupon> wrapper = new LambdaQueryWrapper<Coupon>()
                .eq(type != null, Coupon::getType, type)
                .eq(status != null, Coupon::getStatus, status)
                .eq(shopId != null, Coupon::getShopId, shopId)
                .orderByDesc(Coupon::getId);
        Page<Coupon> page = couponMapper.selectPage(new Page<>(safeNum, safeSize), wrapper);
        return PageResult.of(safeNum, safeSize, page.getTotal(), page.getRecords() == null ? new ArrayList<>() : page.getRecords());
    }
}
