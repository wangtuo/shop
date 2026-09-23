package com.shop.product.freight.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家运费模板新建/编辑请求（B7）。
 */
@Data
public class FreightTemplateSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模板名称 */
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 64, message = "模板名称最长 64 字")
    private String name;

    /** 计费方式：1 按件 2 按重量 3 按体积 */
    @NotNull(message = "计费方式不能为空")
    @Min(value = 1, message = "计费方式非法")
    @Max(value = 3, message = "计费方式非法")
    private Integer chargeType;

    /** 默认首件单位数（必须为正，除零守卫前置到入参） */
    @NotNull(message = "首件单位数不能为空")
    @Min(value = 1, message = "首件单位数必须大于 0")
    private Integer defaultFirst;

    /** 默认首费（分） */
    @NotNull(message = "首费不能为空")
    @Min(value = 0, message = "首费不能为负")
    private Long defaultFirstFee;

    /** 默认续件单位数（必须为正，除零守卫前置到入参） */
    @NotNull(message = "续件单位数不能为空")
    @Min(value = 1, message = "续件单位数必须大于 0")
    private Integer defaultAdd;

    /** 默认续费（分） */
    @NotNull(message = "续费不能为空")
    @Min(value = 0, message = "续费不能为负")
    private Long defaultAddFee;

    /** 满额包邮门槛（分），0 不包邮 */
    @NotNull(message = "满额包邮门槛不能为空")
    @Min(value = 0, message = "满额包邮门槛不能为负")
    private Long freeConditionFen;

    /** 是否设为店铺默认：0 否 1 是（同店唯一） */
    @NotNull(message = "是否默认不能为空")
    @Min(value = 0, message = "是否默认值非法")
    @Max(value = 1, message = "是否默认值非法")
    private Integer isDefault;
}
