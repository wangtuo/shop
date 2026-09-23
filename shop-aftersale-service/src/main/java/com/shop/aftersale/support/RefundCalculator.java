package com.shop.aftersale.support;

import com.shop.common.util.MoneyUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 退款金额计算（design 8.4）。
 *
 * <pre>
 * 可退金额 = 实付金额（含分摊运费） - 已退金额
 * 商品实付 = 商品原价 - 分摊优惠 - 分摊积分抵扣（优惠券不退：基数天然剔除券）
 * 积分：按退款比例退回；满减/折扣已含在实付分摊中
 * </pre>
 */
@Component
public class RefundCalculator {

    /**
     * 明细行退款输入。
     *
     * @param paidTotalFen 该订单明细当前可退基数（实付 - 已退，含分摊运费，分）
     * @param totalQty     明细购买数量
     * @param refundQty    本次售后数量
     */
    public record ItemRefundInput(Long orderItemId, long paidTotalFen, int totalQty, int refundQty) {
    }

    /**
     * 按数量比例计算每行可退金额。整件全退时严格等于 paidTotalFen，部分数量按比例向下取整。
     */
    public List<Long> calcItemRefunds(List<ItemRefundInput> items) {
        List<Long> result = new ArrayList<>(items.size());
        for (ItemRefundInput item : items) {
            if (item.refundQty() <= 0 || item.refundQty() > item.totalQty() || item.paidTotalFen() < 0) {
                throw new IllegalArgumentException("售后数量或可退金额非法: " + item);
            }
            if (item.refundQty() == item.totalQty()) {
                result.add(item.paidTotalFen());
            } else {
                result.add(item.paidTotalFen() * item.refundQty() / item.totalQty());
            }
        }
        return result;
    }

    /**
     * 积分按退款比例退还（满 1 积分才退，向下取整）。
     *
     * @param usedPoints 订单使用积分总量
     * @param refundFen  本次退款（商品口径，不含额外运费补偿）
     * @param baseFen    退款基数（订单商品实付，分）
     */
    public int pointsToRefund(long usedPoints, long refundFen, long baseFen) {
        if (usedPoints <= 0 || refundFen <= 0 || baseFen <= 0) {
            return 0;
        }
        return (int) Math.min(usedPoints, usedPoints * refundFen / baseFen);
    }

    /**
     * 商家责任时的退货运费补偿（买家先行垫付，商家承担）。
     *
     * @param responsibilitySide 责任方
     * @param agreedFreightFen   商家认可的寄回运费（分）
     */
    public long freightCompensation(int responsibilitySide, long agreedFreightFen) {
        if (responsibilitySide == com.shop.api.aftersale.enums.ResponsibilitySide.MERCHANT) {
            return Math.max(0, agreedFreightFen);
        }
        return 0L;
    }

    /**
     * 最大余数法分摊（行内优惠/运费分摊守恒，合计不差 1 分）。
     */
    public List<Long> allocate(long total, List<Long> weights) {
        return MoneyUtils.allocate(total, weights);
    }
}
