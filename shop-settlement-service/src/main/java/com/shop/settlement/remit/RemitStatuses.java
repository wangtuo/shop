package com.shop.settlement.remit;

/** 代发查询终态：10 处理中（已受理未终态）20 成功 30 失败。 */
public final class RemitStatuses {

    public static final int PROCESSING = 10;
    public static final int SUCCESS = 20;
    public static final int FAIL = 30;

    private RemitStatuses() {
    }
}
