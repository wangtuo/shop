package com.shop.marketing.activity.bargain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** C 端发起砍价请求。 */
@Data
public class StartBargainRequest {

    @NotNull(message = "活动ID不能为空")
    @Positive(message = "活动ID必须为正数")
    private Long activityId;
}
