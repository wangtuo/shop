package com.shop.settlement.engine;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 分账计算引擎（独立纯函数组件，design 7.2.2）。
 *
 * <pre>
 * 用户实付金额 = 商品金额 + 运费 - 商户承担优惠 - 平台承担优惠
 * 商户应收     = (商品金额 - 商户承担优惠) × (1 - 佣金率) - 支付通道费 - 技术服务费 + 运费
 * 平台佣金     = (商品金额 - 商户承担优惠) × 佣金率
 * 技术服务费   = 50 分/笔（0.5 元，商户承担，平台收入；design 7.2.1）
 * 支付通道费   = 商品金额 × 0.6%（60bps，商户承担，退款不退）
 * 营销补贴     = 平台承担优惠
 * </pre>
 *
 * <p>资金守恒恒等式（含运费、运费险保费，B11）：
 * <pre>
 * 用户实付(含保费) + 平台营销补贴
 *     = 商户应收 + 平台佣金 + 支付通道费 + 技术服务费 + 运费险保费(平台/保险收入)
 * </pre>
 * 保费为 0 时退化为原恒等式，分账结果与旧实现逐字段一致。
 * 运费险保费仅透传：不进佣金基数（base=商品额-商户承担优惠）、不进通道费基数
 * （商品额×60bps），不可被优惠抵扣，退款时不退、不进退款比例分母。
 * 注意：design 7.2.2 公式文字曾漏写「- 技术服务费」，但 7.2.1 明确技服费是
 * 商户承担的费用、且 SettleClearingExecutor 把它计为平台收入；若不从商户应收
 * 扣减，每单凭空多出 50 分（资金不守恒）。实现以 7.2.1 的承担方定义为准。
 *
 * <p>所有乘法均以「分 × bps / 10000」用 long 精确计算，除法统一 HALF_UP 舍入；
 * 不使用 double/float/BigDecimal 除法，保证性能与确定性。
 */
@Component
public class SplitEngine {

    /** 技术服务费：0.5 元/笔 = 50 分 */
    public static final long TECH_FEE_FEN = 50L;

    /** 支付通道费率：0.6% = 60bps */
    public static final int CHANNEL_FEE_BPS = 60;

    private static final int BPS_BASE = 10000;

    /**
     * 执行分账。
     *
     * @throws BizException 输入金额非法（为负 / 优惠大于商品额 / 佣金率越界）
     */
    public SplitResult split(SplitRequest req) {
        long product = req.getProductAmountFen();
        long freight = req.getFreightFen();
        long merchantBear = req.getMerchantBearDiscountFen();
        long platformBear = req.getPlatformBearDiscountFen();
        int rateBps = req.getCommissionRateBps();
        long insurancePremium = req.getInsurancePremiumFen();

        if (product < 0 || freight < 0 || merchantBear < 0 || platformBear < 0
                || insurancePremium < 0) {
            throw new BizException(ErrorCode.SETTLE_AMOUNT_ERROR, "分账金额不能为负");
        }
        if (merchantBear > product) {
            throw new BizException(ErrorCode.SETTLE_AMOUNT_ERROR, "商户承担优惠不能大于商品金额");
        }
        if (platformBear > product + freight) {
            throw new BizException(ErrorCode.SETTLE_AMOUNT_ERROR, "平台承担优惠不能大于订单金额");
        }
        if (rateBps < 0 || rateBps > BPS_BASE) {
            throw new BizException(ErrorCode.SETTLE_AMOUNT_ERROR, "佣金费率必须在 0~10000bps 之间");
        }

        // 分账基数：商品额 - 商户承担优惠
        long base = product - merchantBear;
        // 平台佣金 = base × 佣金率（HALF_UP）
        long commission = multiplyBps(base, rateBps);
        // 通道费 = 商品额 × 60bps（HALF_UP），商户承担
        long channelFee = multiplyBps(product, CHANNEL_FEE_BPS);
        // 营销补贴 = 平台承担优惠
        long subsidy = platformBear;
        // 商户应收 = base × (1 - 佣金率) - 通道费 - 技术服务费 + 运费
        //         = base - commission - channelFee - techFee + freight
        // （避免二次舍入，恒等式严格守恒；技服费承担方见类注释）
        long merchantReceivable = base - commission - channelFee - TECH_FEE_FEN + freight;
        if (merchantReceivable < 0) {
            throw new BizException(ErrorCode.SETTLE_AMOUNT_ERROR, "分账后商户应收为负，请检查费率与金额");
        }
        // 商品侧用户实付（不含保费）：优惠只能作用于商品/运费，不能抵扣保费
        long goodsUserPay = product + freight - merchantBear - platformBear;
        if (goodsUserPay < 0) {
            throw new BizException(ErrorCode.SETTLE_AMOUNT_ERROR, "用户实付金额为负，优惠总额超出订单金额");
        }
        // 用户实付（含运费险保费），与支付单金额口径一致
        long userPay = goodsUserPay + insurancePremium;

        return SplitResult.builder()
                .productAmountFen(product)
                .freightFen(freight)
                .merchantBearDiscountFen(merchantBear)
                .platformBearDiscountFen(platformBear)
                .userPayFen(userPay)
                .merchantReceivableFen(merchantReceivable)
                .platformCommissionFen(commission)
                .techFeeFen(TECH_FEE_FEN)
                .channelFeeFen(channelFee)
                .marketingSubsidyFen(subsidy)
                .insurancePremiumFen(insurancePremium)
                .commissionRateBps(rateBps)
                .build();
    }

    /**
     * 分 × bps / 10000，HALF_UP 舍入（非负数）。
     */
    public static long multiplyBps(long amountFen, int bps) {
        long numerator = amountFen * bps;
        long quotient = numerator / BPS_BASE;
        long remainder = numerator % BPS_BASE;
        if (remainder * 2 >= BPS_BASE) {
            quotient++;
        }
        return quotient;
    }
}
