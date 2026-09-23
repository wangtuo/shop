package com.shop.pay.feature.recon.enums;

/**
 * 对账状态码（t_pay_recon_batch / t_pay_recon_diff 既有口径，P2-4 仅命名未新增码值）。
 */
public final class ReconStatuses {

    private ReconStatuses() {
    }

    /** 批次：10 拉取/比对中 20 比对完成（仍有未处置差异） 30 差错处置完成。 */
    public static final class Batch {
        public static final int FETCHING = 10;
        public static final int COMPARED = 20;
        public static final int FINISHED = 30;

        private Batch() {
        }
    }

    /** 差异：10 待处理 20 处理中 30 已处理 40 人工挂账（终态）。 */
    public static final class Diff {
        public static final int PENDING = 10;
        public static final int PROCESSING = 20;
        public static final int HANDLED = 30;
        public static final int MANUAL = 40;

        private Diff() {
        }
    }
}
