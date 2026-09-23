package com.shop.product.category.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * SPU 虚拟类目挂载/卸载请求（商户，B13）。
 */
@Data
public class SpuCategoryMountRequest implements Serializable {

    /** 挂载/卸载的类目 ID（一级/二级虚拟类目，三级实体归属走 SPU 主归属） */
    @NotNull(message = "类目 ID 不能为空")
    private Long categoryId;
}
