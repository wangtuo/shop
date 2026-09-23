package com.shop.settlement.remit;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 代发受理结果。渠道仅返回「是否受理 + 渠道流水号」：
 * <b>受理不等于打款成功</b>，终态一律以 {@link RemitChannelClient#query(RemitQueryRequest)} 确认为准。
 */
@Data
@AllArgsConstructor
public class RemitResult {

    /** 是否受理成功（渠道已接单，可进入查询补偿） */
    private boolean accepted;
    /** 渠道代发流水号（受理成功时非空，查询主键，落 t_sett_withdraw/t_sett_deposit_log 的 channel_remit_no） */
    private String channelRemitNo;
    /** 受理失败原因（accepted=false 时） */
    private String failReason;

    public static RemitResult accepted(String channelRemitNo) {
        return new RemitResult(true, channelRemitNo, "");
    }

    public static RemitResult rejected(String failReason) {
        return new RemitResult(false, "", failReason == null ? "渠道受理失败" : failReason);
    }
}
