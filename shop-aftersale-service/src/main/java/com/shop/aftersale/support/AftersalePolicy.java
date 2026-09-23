package com.shop.aftersale.support;

import com.shop.aftersale.aftersale.entity.AftersaleWindow;
import com.shop.aftersale.aftersale.enums.AftersaleCodes;
import com.shop.api.aftersale.enums.AftersaleTypes;
import com.shop.api.aftersale.enums.ResponsibilitySide;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 售后时限/资格/价保/运费险纯规则（design 8.3 / 8.5 / 8.6 / 8.8）。
 */
@Component
public class AftersalePolicy {

    /** 不参与价保的订单类型：秒杀、拼团（活动价本身排除）。 */
    private static final Set<Integer> PRICE_PROTECT_EXCLUDED_ORDER_TYPES = Set.of(2, 3);

    private Clock clock = Clock.systemDefaultZone();

    /** 供测试替换时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ============================ 超时矩阵（8.5） ============================

    public LocalDateTime auditDeadline(LocalDateTime applyTime) {
        return applyTime.plusDays(AftersaleCodes.AUDIT_TIMEOUT_DAYS);
    }

    public LocalDateTime receiveDeadline(LocalDateTime returnShipTime) {
        return returnShipTime.plusDays(AftersaleCodes.RECEIVE_TIMEOUT_DAYS);
    }

    public LocalDateTime exchangeShipDeadline(LocalDateTime merchantReceiveTime) {
        return merchantReceiveTime.plusDays(AftersaleCodes.EXCHANGE_SHIP_TIMEOUT_DAYS);
    }

    public LocalDateTime freeAftersaleDeadline(LocalDateTime confirmTime) {
        return confirmTime.plusDays(AftersaleCodes.FREE_AFTERSALE_DAYS);
    }

    /** 质保截止时间 = 收货时间 + 质保天数（无类目质保数据时按 {@link AftersaleCodes#FREE_AFTERSALE_DAYS} 默认）。 */
    public LocalDateTime warrantyDeadline(LocalDateTime confirmTime, int warrantyDays) {
        return confirmTime.plusDays(warrantyDays);
    }

    public LocalDateTime evidenceDeadline(LocalDateTime applyTime) {
        return applyTime.plusDays(AftersaleCodes.DISPUTE_EVIDENCE_DAYS);
    }

    /** 5 个工作日（跳过周六周日）。 */
    public LocalDateTime arbitrateDeadline(LocalDateTime evidenceDeadline) {
        LocalDateTime d = evidenceDeadline;
        int workdays = 0;
        while (workdays < AftersaleCodes.DISPUTE_ARBITRATE_WORKDAYS) {
            d = d.plusDays(1);
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                workdays++;
            }
        }
        return d;
    }

    public LocalDateTime insuranceClaimDeadline(LocalDateTime refundTime) {
        return refundTime.plusHours(AftersaleCodes.INSURANCE_CLAIM_DELAY_HOURS);
    }

    // ============================ 申请时限（8.3.1） ============================

    /**
     * @param orderCreateTime 订单下单时间（价保期起点，design 8.8：下单后 7 天/大促 30 天）；
     *                        为 null 时回退到窗口投影的创建时间
     * @return 可申请原因；不可申请返回 null
     */
    public String checkApplicable(int type, int orderStatus, AftersaleWindow window,
                                  LocalDateTime orderCreateTime) {
        LocalDateTime now = now();
        switch (orderStatus) {
            case 20: // 待发货：发货前仅退款
                return type == AftersaleTypes.REFUND_ONLY ? null : "发货前仅支持仅退款";
            case 30: // 待收货：收货前 仅退款/退货退款；漏发错发可补发
                if (type == AftersaleTypes.REFUND_ONLY || type == AftersaleTypes.RETURN_REFUND
                        || type == AftersaleTypes.RESHIP) {
                    return null;
                }
                return "收货前不支持该售后类型";
            case 40: // 已完成：收货后 15 天售后期 / 质保期
                return checkAfterCompletion(type, window, orderCreateTime, now);
            default:
                return "当前订单状态不允许申请售后";
        }
    }

    private String checkAfterCompletion(int type, AftersaleWindow w, LocalDateTime orderCreateTime,
                                        LocalDateTime now) {
        if (w == null) {
            return "售后窗口不存在";
        }
        boolean inFree = w.getFreeAftersaleDeadline() != null && !now.isAfter(w.getFreeAftersaleDeadline());
        boolean inWarranty = w.getWarrantyDeadline() != null && !now.isAfter(w.getWarrantyDeadline());
        switch (type) {
            case AftersaleTypes.REFUND_ONLY:
            case AftersaleTypes.RETURN_REFUND:
            case AftersaleTypes.RESHIP:
                return (inFree || inWarranty) ? null : "已超出收货后15天售后期及质保期";
            case AftersaleTypes.EXCHANGE:
                return (inFree || inWarranty) ? null : "已超出换货时限（售后期/质保期）";
            case AftersaleTypes.PRICE_PROTECT: {
                LocalDateTime base = orderCreateTime != null ? orderCreateTime : w.getCreateTime();
                return inPriceProtectWindow(base, w.getOrderType(), now) ? null : "已超出价保期";
            }
            default:
                return "未知售后类型";
        }
    }

    // ============================ 价保（8.8） ============================

    /**
     * 价保期以「下单时间」为起点：普通 7 天，大促（预售）30 天（design 8.8）。
     */
    public boolean inPriceProtectWindow(LocalDateTime orderCreateTime, Integer orderType, LocalDateTime now) {
        if (orderCreateTime == null) {
            return false;
        }
        int days = priceProtectDays(orderType);
        return !now.isAfter(orderCreateTime.plusDays(days));
    }

    public int priceProtectDays(Integer orderType) {
        // 大促订单（预售视为大促长价保期 30 天）
        if (orderType != null && orderType == 4) {
            return AftersaleCodes.PRICE_PROTECT_BIG_PROMO_DAYS;
        }
        return AftersaleCodes.PRICE_PROTECT_DAYS;
    }

    /** 秒杀/拼团订单不享受价保；活动价（秒杀价/拼团价）排除在比价口径外。 */
    public boolean priceProtectSupported(Integer orderType) {
        return orderType == null || !PRICE_PROTECT_EXCLUDED_ORDER_TYPES.contains(orderType);
    }

    /**
     * 价保差价：购买实付单价 - 当前普通售价（绝不取秒杀价/活动价）；未降价返回 0。
     */
    public long priceProtectDiff(long originalUnitFen, long currentNormalPriceFen) {
        return Math.max(0L, originalUnitFen - currentNormalPriceFen);
    }

    // ============================ 运费险（8.6） ============================

    /**
     * 理赔资格：购买运费险且属于 7 天无理由（买家责任）或商家责任退货。
     */
    public boolean insuranceEligible(boolean hasInsurance, int responsibilitySide) {
        return hasInsurance
                && (responsibilitySide == ResponsibilitySide.BUYER
                || responsibilitySide == ResponsibilitySide.MERCHANT);
    }

    /** 理赔金额：实际退货运费与 25 元封顶取小。 */
    public long insuranceClaimFen(long returnFreightFen) {
        return Math.min(AftersaleCodes.INSURANCE_CLAIM_CAP_FEN, Math.max(0L, returnFreightFen));
    }
}
