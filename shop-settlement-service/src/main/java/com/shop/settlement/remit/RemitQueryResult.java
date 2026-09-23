package com.shop.settlement.remit;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 代发终态查询结果。未联调/异常渠道不得返回伪成功。 */
@Data
@AllArgsConstructor
public class RemitQueryResult {

    /** 终态，见 {@link RemitStatuses} */
    private int status;
    private String channelRemitNo;
    private String failReason;

    public static RemitQueryResult processing() {
        return new RemitQueryResult(RemitStatuses.PROCESSING, "", "");
    }

    public static RemitQueryResult success(String channelRemitNo) {
        return new RemitQueryResult(RemitStatuses.SUCCESS, channelRemitNo, "");
    }

    public static RemitQueryResult fail(String channelRemitNo, String failReason) {
        return new RemitQueryResult(RemitStatuses.FAIL, channelRemitNo,
                failReason == null ? "渠道打款失败" : failReason);
    }
}
