package com.shop.marketing.coupon.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 券模板创建/编辑请求。 */
@Data
public class CouponSaveRequest {

    private Long id;
    private Long merchantId;
    private Long shopId;
    @NotBlank(message = "券名称不能为空")
    private String name;
    /** 1满减 2折扣 3无门槛 4免邮 5品类 6店铺 */
    @NotNull
    @Min(value = 1, message = "非法券类型")
    @Max(value = 6, message = "非法券类型")
    private Integer type;
    /** 1全场 2指定SKU 3指定SPU 4指定三级类目 5指定店铺 */
    @NotNull
    @Min(value = 1, message = "非法作用范围")
    @Max(value = 5, message = "非法作用范围")
    private Integer scopeType;
    @PositiveOrZero(message = "面额不能为负")
    private Long faceValueFen;
    @PositiveOrZero(message = "使用门槛不能为负")
    private Long thresholdFen;
    @Min(value = 1, message = "折扣基点取值 1~1000")
    @Max(value = 1000, message = "折扣基点不得大于1000")
    private Integer discountBp;
    @PositiveOrZero(message = "最大优惠金额不能为负")
    private Long maxDiscountFen;
    @PositiveOrZero(message = "发行总量不能为负")
    private Integer totalCount;
    @PositiveOrZero(message = "每人限领不能为负")
    private Integer perUserLimit;
    /** 1主动领取 2活动发放 3新人礼包 4系统补偿 5积分兑换 */
    @Min(value = 1, message = "非法发放方式")
    @Max(value = 5, message = "非法发放方式")
    private Integer issueWay;
    /** 1固定时间段 2领取后N天 */
    @NotNull
    @Min(value = 1, message = "非法有效期类型")
    @Max(value = 2, message = "非法有效期类型")
    private Integer validType;
    @PositiveOrZero(message = "有效天数不能为负")
    private Integer validDays;
    private LocalDateTime validStartTime;
    private LocalDateTime validEndTime;
    @NotNull
    private LocalDateTime receiveStartTime;
    @NotNull
    private LocalDateTime receiveEndTime;
    /** 新人礼包标记：0否 1是（卡 B6，上架为新人券时要求 issueWay=3） */
    @Min(value = 0, message = "newUserGift 仅允许 0/1")
    @Max(value = 1, message = "newUserGift 仅允许 0/1")
    private Integer newUserGift;

    @Valid
    @Size(max = 500, message = "作用目标最多500个")
    private List<Target> targets = new ArrayList<>();

    @Data
    public static class Target {
        @NotNull(message = "目标类型不能为空")
        @Min(value = 1, message = "非法目标类型")
        private Integer targetType;
        @NotNull(message = "目标ID不能为空")
        private Long targetId;
    }
}
