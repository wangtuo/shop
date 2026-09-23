package com.shop.marketing.activity.bargain.dto;

import lombok.Data;

/** 帮砍结果。 */
@Data
public class HelpCutVO {

    /** 本次实际砍下金额（分） */
    private Long cutFen;
    /** 砍后当前价（分） */
    private Long currentPriceFen;
    /** 底价（分） */
    private Long floorPriceFen;
    /** 已帮砍次数 */
    private Integer helpCount;
    /** 是否已达底价（可下单） */
    private Boolean reachedFloor;

    public HelpCutVO() {
    }

    public HelpCutVO(Long cutFen, Long currentPriceFen, Long floorPriceFen,
                     Integer helpCount, Boolean reachedFloor) {
        this.cutFen = cutFen;
        this.currentPriceFen = currentPriceFen;
        this.floorPriceFen = floorPriceFen;
        this.helpCount = helpCount;
        this.reachedFloor = reachedFloor;
    }
}
