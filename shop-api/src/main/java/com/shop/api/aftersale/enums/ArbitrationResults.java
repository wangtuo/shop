package com.shop.api.aftersale.enums;

/**
 * 平台介入仲裁结果。
 *
 * <p>规则来源：design.md 8.7 平台介入：商家已拒绝或超时未处理时用户可申请介入，
 * 双方 3 天内举证，平台 5 个工作日内给出仲裁结果，仲裁为最终结果，双方必须执行。
 */
public final class ArbitrationResults {

    /** 商家胜诉：维持商家处理结果，售后单转拒绝 / 关闭 */
    public static final int MERCHANT_WIN = 1;

    /** 买家胜诉：按买家诉求执行退款 / 退货退款 / 换货 */
    public static final int BUYER_WIN = 2;

    /** 部分支持：按比例部分退款等折中结果 */
    public static final int PARTIAL = 3;

    private ArbitrationResults() {
    }
}
