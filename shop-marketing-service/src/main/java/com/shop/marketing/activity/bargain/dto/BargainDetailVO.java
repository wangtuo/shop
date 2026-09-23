package com.shop.marketing.activity.bargain.dto;

import lombok.Data;

import java.util.List;

/** 砍价详情：当前价/底价/剩余时间/帮砍留痕。 */
@Data
public class BargainDetailVO {

    private Long recordId;
    private Long activityId;
    private Long skuId;
    private Long userId;
    private Long originPriceFen;
    private Long floorPriceFen;
    private Long currentPriceFen;
    private Integer helpCount;
    private Integer helpLimit;
    /** 0砍价中 1已成交 2已失效 */
    private Integer status;
    /** 剩余秒数；&lt;=0 表示已过期 */
    private Long remainSeconds;
    private Boolean reachedFloor;
    private String orderNo;
    private List<HelpView> helps;

    @Data
    public static class HelpView {
        private Long helperUserId;
        private Long cutFen;

        public HelpView() {
        }

        public HelpView(Long helperUserId, Long cutFen) {
            this.helperUserId = helperUserId;
            this.cutFen = cutFen;
        }
    }
}
