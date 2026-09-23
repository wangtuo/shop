package com.shop.marketing.gift;

import com.shop.api.marketing.enums.CouponIssueWays;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 新人礼包发券（卡 B6）。注册事件触发：查 issue_way=3、new_user_gift=1、上架中的券模板，
 * 逐张发券，requestNo={@code NEWUSER:{userId}:{couponId}} 保证每人每券恰 1 张
 * （与 CouponService NEW_USER countHeld 校验、t_user_coupon UK 三重幂等）。
 *
 * <p>券包语义整包同事务：未知异常向上抛出由 MQ 重试；模板已下架/不在领取窗/库存为零/
 * 已持有（relay 以不同 eventId 重发同一注册事件）等业务态跳过并 warn，不阻塞其余券。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewUserGiftService {

    private final CouponMapper couponMapper;
    private final CouponService couponService;

    @Transactional(rollbackFor = Exception.class)
    public void issueGift(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "userId 不能为空");
        }
        List<Coupon> templates = couponMapper.selectNewUserGiftTemplates();
        if (templates == null || templates.isEmpty()) {
            log.warn("新人礼包券模板为空，跳过发券 userId={}", userId);
            return;
        }
        for (Coupon template : templates) {
            // 双保险：SQL 已过滤 issue_way=3，此处再校验防止脏数据误发
            if (template.getIssueWay() == null
                    || template.getIssueWay() != CouponIssueWays.NEW_USER.getCode()) {
                log.warn("新人礼包模板发放方式非NEW_USER，跳过 couponId={} issueWay={}",
                        template.getId(), template.getIssueWay());
                continue;
            }
            String requestNo = "NEWUSER:" + userId + ":" + template.getId();
            try {
                couponService.issue(userId, template.getId(),
                        CouponIssueWays.NEW_USER.getCode(), requestNo);
            } catch (BizException e) {
                // 已领过/领完/下架/不在领取窗：幂等或模板态问题，跳过不阻断其余券与 ACK
                if (e.getCode() == ErrorCode.COUPON_LIMIT.getCode()
                        || e.getCode() == ErrorCode.COUPON_NOT_AVAILABLE.getCode()) {
                    log.warn("新人礼包单券跳过 userId={} couponId={} code={} msg={}",
                            userId, template.getId(), e.getCode(), e.getMessage());
                    continue;
                }
                throw e;
            }
        }
    }
}
