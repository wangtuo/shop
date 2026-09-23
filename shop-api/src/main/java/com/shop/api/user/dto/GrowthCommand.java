package com.shop.api.user.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 成长值增加命令。
 *
 * <p>规则：消费 1 元 = 1 成长值；评价 +10/单；晒单 +20/单；连续签到 7 天 +50。
 * 成长值每年 12 月 31 日按 80% 折算（保底当前等级）。
 *
 * <p>规则来源：design.md 2.1.3 成长值规则。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GrowthCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    /** 业务单号（订单号/评价单号/签到流水等），幂等键 */
    @NotBlank(message = "业务单号不能为空")
    @Size(max = 64, message = "业务单号长度不能超过 64")
    private String bizNo;

    /** 本次增加的成长值（正数） */
    @NotNull(message = "成长值不能为空")
    @Positive(message = "成长值必须为正数")
    private Integer growth;

    /** 成长值获取场景，取值见 {@code GrowthScene} */
    @NotNull(message = "成长值场景不能为空")
    @Min(value = 1, message = "成长值场景取值非法")
    private Integer scene;
}
