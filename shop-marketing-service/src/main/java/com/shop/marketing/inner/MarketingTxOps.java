package com.shop.marketing.inner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.marketing.dto.PromotionConfirmCommand;
import com.shop.api.marketing.dto.PromotionLockCommand;
import com.shop.api.marketing.dto.PromotionReleaseCommand;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.marketing.activity.service.GroupbuyService;
import com.shop.marketing.activity.service.PresaleService;
import com.shop.marketing.activity.service.SeckillService;
import com.shop.marketing.common.entity.MarketingLock;
import com.shop.marketing.common.mapper.MarketingLockMapper;
import com.shop.marketing.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 营销对内编排的事务边界（P1-6 修复）。
 *
 * <p>本 Bean 的方法持有 {@code @Transactional}，由 {@link MarketingAppService} 在 orderNo
 * 分布式锁内通过 Spring 代理调用——锁在事务开启前获取、事务提交（或回滚）完成后才释放，
 * 消除「锁在 @Transactional 方法内、finally 释放早于事务提交」的窗口；同 orderNo 的并发
 * 重复请求在锁外串行化，第二个请求在提交完成后才能看到 t_marketing_lock 占坑并幂等返回。
 *
 * <p><b>禁止类内自调用</b>：必须由 MarketingAppService 经代理调用，否则事务注解失效。
 */
@Service
@RequiredArgsConstructor
public class MarketingTxOps {

    private final MarketingLockMapper lockMapper;
    private final CouponService couponService;
    private final SeckillService seckillService;
    private final GroupbuyService groupbuyService;
    private final PresaleService presaleService;

    /** 下单：预核销券 + 锁秒杀库存/开团参团/预售定金登记。 */
    @Transactional(rollbackFor = Exception.class)
    public void lockInTx(PromotionLockCommand cmd) {
        MarketingLock existed = lockMapper.selectOne(new LambdaQueryWrapper<MarketingLock>()
                .eq(MarketingLock::getOrderNo, cmd.getOrderNo()));
        if (existed != null) {
            // orderNo 幂等：重复下单事件/重试（在锁内串行后一定能读到已提交占坑行）直接返回成功
            return;
        }
        List<Long> couponIds = cmd.getUserCouponIds() == null ? List.of() : cmd.getUserCouponIds();

        int orderType = cmd.getOrderType() == null ? 1 : cmd.getOrderType();
        // W4-4/B4：活动单（2秒杀/3拼团/4预售）缺活动 ID 必须硬失败，禁止静默按普通价/普通库存下成活动单
        if ((orderType == 2 || orderType == 3 || orderType == 4) && cmd.getActivityId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "活动订单缺少活动 ID，拒绝按普通资源下单：orderType=" + orderType + " orderNo=" + cmd.getOrderNo());
        }

        couponService.lockCoupons(cmd.getUserId(), cmd.getOrderNo(), couponIds);

        if (orderType == 2) {
            seckillService.lock(cmd.getUserId(), cmd.getActivityId(), cmd.getOrderNo(), cmd.getItems());
        } else if (orderType == 3) {
            groupbuyService.openOrJoin(cmd.getUserId(), cmd.getActivityId(), cmd.getOrderNo());
        } else if (orderType == 4) {
            presaleService.register(cmd.getUserId(), cmd.getActivityId(), cmd.getOrderNo());
        }

        MarketingLock lock = new MarketingLock();
        lock.setOrderNo(cmd.getOrderNo());
        lock.setUserId(cmd.getUserId());
        lock.setOrderType(orderType);
        lock.setActivityId(cmd.getActivityId());
        lock.setUserCouponIds(joinIds(couponIds));
        lock.setSnapshotJson(cmd.getSnapshotJson());
        lock.setStatus(0);
        lockMapper.insert(lock);
    }

    /** 支付成功：核销券/秒杀扣减/拼团成团推进/预售尾款核销。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirmInTx(PromotionConfirmCommand cmd) {
        MarketingLock lock = lockMapper.selectOne(new LambdaQueryWrapper<MarketingLock>()
                .eq(MarketingLock::getOrderNo, cmd.getOrderNo()));
        if (lock == null || lock.getStatus() != 0) {
            return;
        }
        couponService.confirmCoupons(cmd.getOrderNo(), splitIds(lock.getUserCouponIds()));
        switch (lock.getOrderType() == null ? 1 : lock.getOrderType()) {
            case 2 -> seckillService.confirm(cmd.getOrderNo());
            case 4 -> presaleService.confirm(cmd.getOrderNo());
            default -> { /* 普通/拼团无额外资源扣减 */ }
        }
        lockMapper.updateStatus(cmd.getOrderNo(), 0, 1);
    }

    /** 取消/超时：券退回未使用、秒杀锁定回可售、拼团成员退出、预售定金不退。 */
    @Transactional(rollbackFor = Exception.class)
    public void releaseInTx(PromotionReleaseCommand cmd) {
        MarketingLock lock = lockMapper.selectOne(new LambdaQueryWrapper<MarketingLock>()
                .eq(MarketingLock::getOrderNo, cmd.getOrderNo()));
        if (lock == null || lock.getStatus() != 0) {
            // 未找到（已确认/已释放/无营销资源）按成功返回，保证取消链路可重试
            return;
        }
        couponService.releaseCoupons(cmd.getOrderNo(), splitIds(lock.getUserCouponIds()));
        switch (lock.getOrderType() == null ? 1 : lock.getOrderType()) {
            case 2 -> seckillService.release(cmd.getOrderNo());
            case 3 -> groupbuyService.release(cmd.getOrderNo());
            case 4 -> { /* 买家原因定金不退，预售单留给尾款超时任务处理 */ }
            default -> { }
        }
        lockMapper.updateStatus(cmd.getOrderNo(), 0, 2);
    }

    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private List<Long> splitIds(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        List<Long> ids = new ArrayList<>();
        for (String s : Arrays.asList(text.split(","))) {
            if (!s.isBlank()) {
                ids.add(Long.parseLong(s.trim()));
            }
        }
        return ids;
    }
}
