package com.shop.marketing.activity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 营销活动创建/编辑请求（秒杀 SKU 库存通过独立接口配置）。 */
@Data
public class ActivitySaveRequest {

    private Long id;
    private Long merchantId;
    private Long shopId;
    @NotBlank
    private String name;
    /** 10秒杀 11拼团 12预售 13砍价 14抽奖 */
    @NotNull
    @Min(value = 10, message = "非法活动类型")
    @Max(value = 14, message = "非法活动类型")
    private Integer type;
    @NotNull
    private LocalDateTime startTime;
    @NotNull
    private LocalDateTime endTime;
    /** 规则 JSON 字符串（拼团人数/团长优惠、预售定金膨胀尾款窗口等） */
    private String ruleJson;
    /** 到点是否自动结束：0否 1是（W4-4 秒杀 Job 消费） */
    @Min(value = 0, message = "autoEnd 仅允许 0/1")
    @Max(value = 1, message = "autoEnd 仅允许 0/1")
    private Integer autoEnd;

    @Valid
    @Size(max = 200, message = "秒杀SKU最多200个")
    private List<SeckillSkuRequest> seckillSkus = new ArrayList<>();

    @Data
    public static class SeckillSkuRequest {
        @NotNull(message = "skuId 不能为空")
        private Long skuId;
        @NotNull(message = "秒杀价不能为空")
        @Positive(message = "秒杀价必须为正数")
        private Long seckillPriceFen;
        @NotNull(message = "秒杀库存不能为空")
        @Min(value = 1, message = "秒杀库存至少为1")
        private Integer totalStock;
    }
}
