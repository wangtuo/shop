package com.shop.marketing.activity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageQuery;
import com.shop.common.result.PageResult;
import com.shop.marketing.activity.dto.ActivitySaveRequest;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.SeckillSku;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.SeckillSkuMapper;
import com.shop.marketing.activity.support.SeckillStockClient;
import com.shop.marketing.support.MarketingAuditStatus;
import com.shop.marketing.support.MarketingStatusMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;

/** 商户/平台活动管理：CRUD + 上下架 + 秒杀 SKU 库存配置 + 分页。 */
@Service
@RequiredArgsConstructor
public class ActivityAdminService {

    private final ActivityMapper activityMapper;
    private final SeckillSkuMapper seckillSkuMapper;
    private final SeckillStockClient stockClient;

    @Transactional(rollbackFor = Exception.class)
    public Long save(ActivitySaveRequest req) {
        if (!req.getEndTime().isAfter(req.getStartTime())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "活动结束时间必须晚于开始时间");
        }
        Activity activity = new Activity();
        activity.setId(req.getId());
        // 平台活动（平台管理员后台创建）以 merchantId=0 为哨兵，与 t_coupon 平台券口径一致
        activity.setMerchantId(req.getMerchantId() == null ? 0L : req.getMerchantId());
        activity.setShopId(req.getShopId());
        activity.setName(req.getName());
        activity.setType(req.getType());
        activity.setStartTime(req.getStartTime());
        activity.setEndTime(req.getEndTime());
        activity.setRuleJson(req.getRuleJson());
        // auto_end 未上送时不写列，沿用 DDL 默认 1（秒杀到点自动结束，W4-4 消费）
        activity.setAutoEnd(req.getAutoEnd());
        if (req.getId() == null) {
            activity.setStatus(0);
            // 平台管理员后台直建：自动审核通过，保持存量平台直建能力不回退（B2）
            activity.setAuditStatus(MarketingAuditStatus.APPROVED.getCode());
            activityMapper.insert(activity);
        } else if (activityMapper.updateById(activity) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        if (req.getType() == 10 && req.getSeckillSkus() != null && !req.getSeckillSkus().isEmpty()) {
            seckillSkuMapper.delete(new LambdaQueryWrapper<SeckillSku>()
                    .eq(SeckillSku::getActivityId, activity.getId()));
            for (ActivitySaveRequest.SeckillSkuRequest skuReq : req.getSeckillSkus()) {
                SeckillSku sku = new SeckillSku();
                sku.setActivityId(activity.getId());
                sku.setSkuId(skuReq.getSkuId());
                sku.setSeckillPriceFen(skuReq.getSeckillPriceFen());
                sku.setTotalStock(skuReq.getTotalStock());
                sku.setLockedStock(0);
                sku.setSoldStock(0);
                seckillSkuMapper.insert(sku);
                stockClient.initStock(activity.getId(), skuReq.getSkuId(), skuReq.getTotalStock());
            }
        }
        return activity.getId();
    }

    /**
     * 业务状态变更（API-M + B2）：0下架/1进行中/2已结束/3已取消。
     * 目标态白名单与迁移合法性统一由 {@link MarketingStatusMachine} 校验；
     * 审核未通过（audit_status≠2）禁止任何业务状态变更；Mapper CAS 兜底并发。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer toStatus) {
        if (toStatus == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 不能为空");
        }
        Activity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        if (activity.getStatus() != null && activity.getStatus() == toStatus) {
            return;
        }
        MarketingStatusMachine.assertBizTransition(MarketingStatusMachine.Domain.ACTIVITY,
                activity.getAuditStatus(), activity.getStatus() == null ? 0 : activity.getStatus(), toStatus);
        if (activityMapper.updateStatus(id, activity.getStatus(), toStatus) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "活动状态已变更，请刷新重试");
        }
    }

    public PageResult<Activity> page(long pageNum, long pageSize, Integer type, Integer status, Long shopId) {
        PageQuery pq = new PageQuery();
        pq.setPageNum((int) pageNum);
        pq.setPageSize((int) pageSize);
        int safeNum = pq.safePageNum();
        int safeSize = pq.safePageSize();
        LambdaQueryWrapper<Activity> wrapper = new LambdaQueryWrapper<Activity>()
                .eq(type != null, Activity::getType, type)
                .eq(status != null, Activity::getStatus, status)
                .eq(shopId != null, Activity::getShopId, shopId)
                .orderByDesc(Activity::getId);
        Page<Activity> page = activityMapper.selectPage(new Page<>(safeNum, safeSize), wrapper);
        return PageResult.of(safeNum, safeSize, page.getTotal(), page.getRecords() == null ? new ArrayList<>() : page.getRecords());
    }
}
