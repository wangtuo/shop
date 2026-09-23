package com.shop.marketing.activity.lottery.dto;

import lombok.Data;

/** 抽奖结果。prizeType=3 或全部有量奖品罄尽降级时 prizeCode 可为空（谢谢参与）。 */
@Data
public class DrawResult {

    private Long recordId;
    /** 1 积分 2 优惠券 3 谢谢参与 */
    private Integer prizeType;
    private String prizeCode;
    private String prizeName;
    private Integer costPoints;

    public DrawResult() {
    }

    public DrawResult(Long recordId, Integer prizeType, String prizeCode,
                      String prizeName, Integer costPoints) {
        this.recordId = recordId;
        this.prizeType = prizeType;
        this.prizeCode = prizeCode;
        this.prizeName = prizeName;
        this.costPoints = costPoints;
    }
}
