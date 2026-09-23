package com.shop.marketing.promo.dto;

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

/** 促销创建/编辑请求（含规则档位与作用目标）。 */
@Data
public class PromoSaveRequest {

    private Long id;
    private Long merchantId;
    private Long shopId;
    @NotBlank(message = "促销名称不能为空")
    private String name;
    /** 1满减 2满折 3满赠 4第N件 5限时折扣 */
    @NotNull(message = "促销类型不能为空")
    @Min(value = 1, message = "非法促销类型")
    @Max(value = 5, message = "非法促销类型")
    private Integer type;
    /** 1全部商品 2指定SKU 3指定SPU 4指定三级类目 */
    @NotNull(message = "作用范围不能为空")
    @Min(value = 1, message = "非法作用范围")
    @Max(value = 4, message = "非法作用范围")
    private Integer scopeType;
    @NotNull
    private LocalDateTime startTime;
    @NotNull
    private LocalDateTime endTime;
    private String remark;

    @Valid
    @Size(max = 50, message = "促销档位最多50个")
    private List<Level> levels = new ArrayList<>();
    @Valid
    @Size(max = 500, message = "作用目标最多500个")
    private List<Target> targets = new ArrayList<>();

    @Data
    public static class Level {
        @PositiveOrZero(message = "门槛金额不能为负")
        private Long thresholdFen;
        @PositiveOrZero(message = "减免金额不能为负")
        private Long reduceFen;
        @Min(value = 1, message = "折扣基点取值 1~1000")
        @Max(value = 1000, message = "折扣基点不得大于1000")
        private Integer discountBp;
        @Min(value = 2, message = "第N件N至少为2")
        private Integer nthIndex;
        @Min(value = 1, message = "赠品SKU非法")
        private Long giftSkuId;
        @PositiveOrZero(message = "赠品数量不能为负")
        private Integer giftQty;
    }

    @Data
    public static class Target {
        /** 1SKU 2SPU 3三级类目 */
        @NotNull(message = "目标类型不能为空")
        @Min(value = 1, message = "非法目标类型")
        private Integer targetType;
        @NotNull(message = "目标ID不能为空")
        private Long targetId;
    }
}
