package com.shop.marketing.activity.lottery.dto;

import lombok.Data;

/** 奖品余量视图。谢谢参与/不限量奖品 remain=null（不展示余量）。 */
@Data
public class PrizeStockVO {

    private String prizeCode;
    private String prizeName;
    /** 1 积分 2 优惠券 3 谢谢参与 */
    private Integer prizeType;
    /** 剩余库存；null=不限量或谢谢参与（不展示） */
    private Integer remain;

    public PrizeStockVO() {
    }

    public PrizeStockVO(String prizeCode, String prizeName, Integer prizeType, Integer remain) {
        this.prizeCode = prizeCode;
        this.prizeName = prizeName;
        this.prizeType = prizeType;
        this.remain = remain;
    }
}
