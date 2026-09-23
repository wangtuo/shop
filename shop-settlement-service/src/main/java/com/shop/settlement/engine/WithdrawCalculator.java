package com.shop.settlement.engine;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 提现规则纯函数组件（design 7.4）。
 * <ul>
 *   <li>最低提现 100 元（10000 分）；</li>
 *   <li>单日累计上限 50 万元（50000000 分）；</li>
 *   <li>每自然月前 3 笔免手续费，第 4 笔起金额 × 0.1%（10bps），单笔最低 2 元（200 分）。</li>
 * </ul>
 */
@Component
public class WithdrawCalculator {

    /** 最低提现金额：100 元 = 10000 分 */
    public static final long MIN_AMOUNT_FEN = 10000L;

    /** 单日提现上限：50 万元 = 50000000 分 */
    public static final long DAILY_LIMIT_FEN = 50_000_000L;

    /** 每自然月免费笔数 */
    public static final int FREE_TIMES_PER_MONTH = 3;

    /** 手续费率：0.1% = 10bps */
    public static final int FEE_BPS = 10;

    /** 手续费单笔最低：2 元 = 200 分 */
    public static final long MIN_FEE_FEN = 200L;

    /**
     * 校验提现金额与单日额度。
     *
     * @param amountFen       本次提现金额（分）
     * @param dailyAlreadyFen 当日已申请累计金额（分）
     */
    public void validateAmount(long amountFen, long dailyAlreadyFen) {
        if (amountFen < MIN_AMOUNT_FEN) {
            throw new BizException(ErrorCode.WITHDRAW_LIMIT, "提现金额不能低于100元");
        }
        if (dailyAlreadyFen < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当日已提现金额不能为负");
        }
        if (dailyAlreadyFen + amountFen > DAILY_LIMIT_FEN) {
            throw new BizException(ErrorCode.WITHDRAW_LIMIT, "单日提现金额不能超过50万元");
        }
    }

    /**
     * 计算手续费。
     *
     * @param amountFen              本次提现金额（分）
     * @param alreadyAppliedThisMonth 本自然月此前已申请笔数（不含本次）
     * @return 手续费（分），免费笔返回 0
     */
    public long fee(long amountFen, int alreadyAppliedThisMonth) {
        if (amountFen < MIN_AMOUNT_FEN) {
            throw new BizException(ErrorCode.WITHDRAW_LIMIT, "提现金额不能低于100元");
        }
        if (alreadyAppliedThisMonth < FREE_TIMES_PER_MONTH) {
            return 0L;
        }
        long fee = SplitEngine.multiplyBps(amountFen, FEE_BPS);
        return Math.max(fee, MIN_FEE_FEN);
    }

    /** 本笔是否在每月前 3 笔免费额度内。 */
    public boolean freeOfCharge(int alreadyAppliedThisMonth) {
        return alreadyAppliedThisMonth < FREE_TIMES_PER_MONTH;
    }
}
