package com.shop.product.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 类目新增/编辑请求。三级类目体系，层级由父类目推导，最深三级。
 */
@Data
public class CategorySaveRequest implements Serializable {

    /** 父类目 ID，0 或空表示创建一级类目 */
    private Long pid;

    /** 类目名称 */
    @NotBlank(message = "类目名称不能为空")
    @Size(max = 64, message = "类目名称最长 64 字")
    private String name;

    /** 类目图标 URL */
    @Size(max = 512, message = "图标 URL 过长")
    private String icon;

    /** 排序，升序 */
    private Integer sort;

    /** 状态：0 停用 1 启用，默认启用 */
    private Integer status;

    /** 关键属性名（如 品牌、型号） */
    private List<String> keyAttrs = new ArrayList<>();

    /** 销售属性名（如 颜色、尺码） */
    private List<String> saleAttrs = new ArrayList<>();

    /** 非关键属性名（如 产地、保修期） */
    private List<String> attrs = new ArrayList<>();
}
