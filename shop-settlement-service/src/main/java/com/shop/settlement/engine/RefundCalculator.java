package com.shop.settlement.engine;

import com.shop.api.pay.enums.RefundTypes;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 退款清算冲正纯函数组件（design 7.5）。
 *
 * <ul>
 *   <li>按退款金额占订单实付比例分别冲正：平台佣金按比例回退、营销补贴按比例回营销账户；</li>
 *   <li>技术服务费不退、支付通道费不退；</li>
 *   <li>商户应收货款按比例扣回（先待结算，不足扣保证金，由 Service 层做瀑布）；</li>
 *   <li>全额退款清算单 stage→40，部分退款保持原 stage 并累计冲正明细。</li>
 * </ul>
 * 比例与各项金额一律 long 精确计算，乘法 fen×bps/10000，HALF_UP 舍入。
 */
@Component
public class RefundCalculator {

    private static final int BPS_BASE = 10000;

    /**
     * @param refundFen           本次退款金额（分，不含运费险保费——保费不退）
     * @param orderPayFen         订单实付金额（分，清算单 pay_amount_fen）
     * @param merchantReceivable  清算单商户应收货款（分）
     * @param commission          清算单平台佣金（分）
     * @param subsidy             清算单营销补贴（分）
     * @param refundType          退款类型 {@link RefundTypes}（1 全额 2 部分）
     * @param alreadyRefundedFen  该清算单此前已退款累计（分），用于防超额
     */
    public RefundReverseResult calculate(long refundFen, long orderPayFen,
                                         long merchantReceivable, long commission, long subsidy,
                                         int refundType, long alreadyRefundedFen) {
        return calculate(refundFen, orderPayFen, 0L,
                merchantReceivable, commission, subsidy, refundType, alreadyRefundedFen);
    }

    /**
     * 带运费险保费的退款冲正计算（B11）。
     *
     * @param insurancePremiumFen 清算单运费险保费（分）。保费不退：先从订单实付中剔除，
     *                            退款比例分母与累计退款上限均<b>不含</b>保费；
     *                            RefundSucceededEvent 的退款金额本身也不含保费。
     */
    public RefundReverseResult calculate(long refundFen, long orderPayFen, long insurancePremiumFen,
                                         long merchantReceivable, long commission, long subsidy,
                                         int refundType, long alreadyRefundedFen) {
        if (insurancePremiumFen < 0 || insurancePremiumFen > orderPayFen) {
            throw new BizException(ErrorCode.SETTLE_AMOUNT_ERROR, "运费险保费金额异常");
        }
        // 退款比例分母 = 商品侧实付（pay_amount_fen 含保费，必须剔除），保费不进分母
        long goodsPayFen = orderPayFen - insurancePremiumFen;
        if (goodsPayFen <= 0) {
            throw new BizException(ErrorCode.SETTLE_AMOUNT_ERROR, "订单商品实付金额异常，无法计算退款比例");
        }
        if (refundFen <= 0) {
            throw new BizException(ErrorCode.REFUND_AMOUNT_ERROR, "退款金额必须大于0");
        }
        if (alreadyRefundedFen < 0 || alreadyRefundedFen + refundFen > goodsPayFen) {
            throw new BizException(ErrorCode.REFUND_AMOUNT_ERROR, "累计退款金额超出订单商品实付金额");
        }
        boolean full = refundType == RefundTypes.FULL.getCode()
                || alreadyRefundedFen + refundFen >= goodsPayFen;

        int ratioBps;
        if (full) {
            // 全额：比例 100%，各分项直接全额冲正，避免尾差导致 1 分钱挂账
            ratioBps = BPS_BASE;
        } else {
            // 比例 = 退款 / 商品侧实付 × 10000，HALF_UP
            ratioBps = ratioHalfUp(refundFen, goodsPayFen);
        }

        long merchantPart = full ? merchantReceivable
                : SplitEngine.multiplyBps(merchantReceivable, ratioBps);
        long commissionPart = full ? commission
                : SplitEngine.multiplyBps(commission, ratioBps);
        long subsidyPart = full ? subsidy
                : SplitEngine.multiplyBps(subsidy, ratioBps);

        return RefundReverseResult.builder()
                .refundRatioBps(Math.min(ratioBps, BPS_BASE))
                .merchantPartFen(merchantPart)
                .commissionReverseFen(commissionPart)
                .subsidyReverseFen(subsidyPart)
                .techFeeReverseFen(0L)
                .channelFeeReverseFen(0L)
                .fullRefund(full)
                .build();
    }

    /** refundFen / payFen × 10000，HALF_UP，上限 10000bps。 */
    static int ratioHalfUp(long refundFen, long payFen) {
        long numerator = refundFen * BPS_BASE;
        long q = numerator / payFen;
        long r = numerator % payFen;
        if (r * 2 >= payFen) {
            q++;
        }
        return (int) Math.min(q, BPS_BASE);
    }
}
