package com.shop.product.category.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 规格/属性主数据新增/编辑请求（平台运营，B13）。
 */
@Data
public class AttrKeySaveRequest implements Serializable {

    /** 所属类目 ID，0=全局通用属性 */
    @NotNull(message = "类目 ID 不能为空（0 表示全局通用属性）")
    private Long categoryId;

    /** 属性名（颜色/尺码/材质…） */
    @NotBlank(message = "属性名不能为空")
    @Size(max = 64, message = "属性名最长 64 字")
    private String name;

    /** 属性类型：1 关键属性 2 销售规格(SKU) 3 普通属性(SPU) */
    @NotNull(message = "属性类型不能为空")
    @Min(value = 1, message = "非法属性类型")
    @Max(value = 3, message = "非法属性类型")
    private Integer attrType;

    /** 值类型：1 枚举 2 数值 3 文本，默认枚举 */
    @Min(value = 1, message = "非法值类型")
    @Max(value = 3, message = "非法值类型")
    private Integer valueType = 1;

    /** 枚举可选值列表（valueType=1 时必填） */
    private List<String> valueOptions = new ArrayList<>();

    /** 数值单位（valueType=2 时使用，如 g/mm） */
    @Size(max = 16, message = "单位最长 16 字")
    private String unit;

    /** 是否必填：0 否 1 是 */
    private Integer requiredFlag = 0;

    /** 排序，升序 */
    private Integer sort = 0;

    /** 状态：0 停用 1 启用，默认启用 */
    private Integer status = 1;
}
