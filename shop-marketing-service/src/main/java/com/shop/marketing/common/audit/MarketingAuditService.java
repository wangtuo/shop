package com.shop.marketing.common.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.promo.entity.Promo;
import com.shop.marketing.promo.mapper.PromoMapper;
import com.shop.marketing.support.MarketingAuditStatus;
import com.shop.marketing.support.MarketingStatusMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 营销三表审核流（卡 B2）：商户提交（草稿/驳回 → 待审核）、平台通过/驳回（仅待审核可审批）。
 * 所有审核态翻转走 {@link MarketingStatusMachine} 白名单 + Mapper CAS，影响 0 行即冲突。
 */
@Service
@RequiredArgsConstructor
public class MarketingAuditService {

    private final ActivityMapper activityMapper;
    private final PromoMapper promoMapper;
    private final CouponMapper couponMapper;

    // ---------------- 商户提交 ----------------

    @Transactional(rollbackFor = Exception.class)
    public void submitActivity(Long id, Long merchantId) {
        Activity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        requireOwner(activity.getMerchantId(), merchantId);
        requireSubmittable(activity.getAuditStatus());
        if (activityMapper.casSubmitAudit(id, LocalDateTime.now()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "活动审核状态已变更，请刷新重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void submitPromo(Long id, Long merchantId) {
        Promo promo = promoMapper.selectById(id);
        if (promo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "促销不存在");
        }
        requireOwner(promo.getMerchantId(), merchantId);
        requireSubmittable(promo.getAuditStatus());
        if (promoMapper.casSubmitAudit(id, LocalDateTime.now()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "促销审核状态已变更，请刷新重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void submitCoupon(Long id, Long merchantId) {
        Coupon coupon = couponMapper.selectById(id);
        if (coupon == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券不存在");
        }
        requireOwner(coupon.getMerchantId(), merchantId);
        requireSubmittable(coupon.getAuditStatus());
        if (couponMapper.casSubmitAudit(id, LocalDateTime.now()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "券审核状态已变更，请刷新重试");
        }
    }

    // ---------------- 平台审批 ----------------

    @Transactional(rollbackFor = Exception.class)
    public void approve(MarketingStatusMachine.Domain domain, Long id, Long auditUserId) {
        audit(domain, id, MarketingAuditStatus.APPROVED.getCode(), auditUserId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(MarketingStatusMachine.Domain domain, Long id, Long auditUserId, String remark) {
        if (remark == null || remark.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "驳回原因不能为空");
        }
        audit(domain, id, MarketingAuditStatus.REJECTED.getCode(), auditUserId, remark);
    }

    private void audit(MarketingStatusMachine.Domain domain, Long id, int toAudit,
                       Long auditUserId, String remark) {
        int rows;
        LocalDateTime now = LocalDateTime.now();
        switch (domain) {
            case ACTIVITY -> {
                requireExists(activityMapper.selectById(id), "活动不存在");
                rows = activityMapper.casAudit(id, toAudit, auditUserId, now, remark);
            }
            case PROMO -> {
                requireExists(promoMapper.selectById(id), "促销不存在");
                rows = promoMapper.casAudit(id, toAudit, auditUserId, now, remark);
            }
            case COUPON -> {
                requireExists(couponMapper.selectById(id), "优惠券不存在");
                rows = couponMapper.casAudit(id, toAudit, auditUserId, now, remark);
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "非法审核类型");
        }
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "单据不在待审核状态，请刷新重试");
        }
    }

    /** 待审/按审核态分页（平台审核台）。 */
    public PageResult<?> page(MarketingStatusMachine.Domain domain, Integer auditStatus,
                              long pageNum, long pageSize) {
        Integer audit = auditStatus == null ? MarketingAuditStatus.PENDING.getCode() : auditStatus;
        return switch (domain) {
            case ACTIVITY -> {
                Page<Activity> page = activityMapper.selectPage(new Page<>(pageNum, pageSize),
                        new LambdaQueryWrapper<Activity>()
                                .eq(Activity::getAuditStatus, audit)
                                .orderByAsc(Activity::getSubmitTime));
                yield PageResult.of(pageNum, pageSize, page.getTotal(), page.getRecords());
            }
            case PROMO -> {
                Page<Promo> page = promoMapper.selectPage(new Page<>(pageNum, pageSize),
                        new LambdaQueryWrapper<Promo>()
                                .eq(Promo::getAuditStatus, audit)
                                .orderByAsc(Promo::getSubmitTime));
                yield PageResult.of(pageNum, pageSize, page.getTotal(), page.getRecords());
            }
            case COUPON -> {
                Page<Coupon> page = couponMapper.selectPage(new Page<>(pageNum, pageSize),
                        new LambdaQueryWrapper<Coupon>()
                                .eq(Coupon::getAuditStatus, audit)
                                .orderByAsc(Coupon::getSubmitTime));
                yield PageResult.of(pageNum, pageSize, page.getTotal(), page.getRecords());
            }
        };
    }

    private void requireOwner(Long ownerMerchantId, Long loginMerchantId) {
        if (loginMerchantId == null || !loginMerchantId.equals(ownerMerchantId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅所属商户可提交该单据");
        }
    }

    private void requireSubmittable(Integer auditStatus) {
        if (auditStatus == null || !MarketingStatusMachine.canSubmit(auditStatus)) {
            throw new BizException(ErrorCode.CONFLICT, "仅草稿/驳回状态可提交审核");
        }
    }

    private void requireExists(Object entity, String message) {
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, message);
        }
    }
}
