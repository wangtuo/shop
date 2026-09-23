package com.shop.product.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 品牌新增/编辑请求。
 */
@Data
public class BrandSaveRequest implements Serializable {

    @NotBlank(message = "品牌名称不能为空")
    @Size(max = 128, message = "品牌名称最长 128 字")
    private String name;

    @Size(max = 512, message = "LOGO URL 过长")
    private String logo;

    @Size(max = 8, message = "首字母最长 8 字符")
    private String initial;

    private Integer sort;

    /** 0 停用 1 启用 */
    private Integer status;
}
