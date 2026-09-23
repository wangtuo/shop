package com.shop.product.freight.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 区域运费规则新建/编辑请求（B7）。regionCodes 为行政区划编码列表。
 */
@Data
public class FreightRegionSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 适用行政区划编码列表（省/市/区编码，至少 1 个） */
    @NotEmpty(message = "区域编码不能为空")
    @Size(max = 500, message = "区域编码数量超限")
    private List<@NotBlank(message = "区域编码不能为空") @Size(max = 12, message = "区域编码最长 12 位") String> regionCodes;

    /** 首件单位数（必须为正） */
    @NotNull(message = "首件单位数不能为空")
    @Min(value = 1, message = "首件单位数必须大于 0")
    private Integer firstUnit;

    /** 首费（分） */
    @NotNull(message = "首费不能为空")
    @Min(value = 0, message = "首费不能为负")
    private Long firstFeeFen;

    /** 续件单位数（必须为正） */
    @NotNull(message = "续件单位数不能为空")
    @Min(value = 1, message = "续件单位数必须大于 0")
    private Integer addUnit;

    /** 续费（分） */
    @NotNull(message = "续费不能为空")
    @Min(value = 0, message = "续费不能为负")
    private Long addFeeFen;

    /** 是否可配送：0 不可配送（拒单） 1 可配送 */
    @NotNull(message = "可配送标记不能为空")
    @Min(value = 0, message = "可配送标记非法")
    @Max(value = 1, message = "可配送标记非法")
    private Integer deliverable;
}
