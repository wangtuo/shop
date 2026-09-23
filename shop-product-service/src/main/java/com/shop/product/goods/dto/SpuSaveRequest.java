package com.shop.product.goods.dto;

import com.shop.common.validation.MediaUrls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * SPU 新增/编辑请求（商户端）。
 */
@Data
public class SpuSaveRequest implements Serializable {

    /** 店铺 ID（商户名下店铺） */
    @NotNull(message = "店铺 ID 不能为空")
    private Long shopId;

    /** 商品名称 */
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 120, message = "商品名称最长 120 字")
    private String name;

    /** 品牌 ID */
    @NotNull(message = "品牌不能为空")
    private Long brandId;

    /** 三级类目 ID */
    @NotNull(message = "三级类目不能为空")
    private Long category3Id;

    /**
     * 额外虚拟挂载类目 ID 列表（B13，一级/二级虚拟类目）；主归属 {@link #category3Id}
     * 自动剔除，最多 20 个；null 表示不改动既有挂载。
     */
    @Size(max = 20, message = "虚拟挂载类目最多 20 个")
    private List<Long> categoryIds;

    /** 主图 URL */
    @NotBlank(message = "主图不能为空")
    @Pattern(regexp = MediaUrls.URL_PATTERN, message = "主图必须为 http/https URL")
    @Size(max = MediaUrls.URL_MAX_LENGTH, message = "主图地址长度不能超过 512")
    private String mainImage;

    /** 轮播图 URL 列表（最多 10 张，元素必须为 http/https URL） */
    @Valid
    @Size(max = MediaUrls.LIST_MAX, message = "图片数量不能超过 10")
    private List<@Pattern(regexp = MediaUrls.URL_PATTERN,
            message = "轮播图必须为 http/https URL") String> images = new ArrayList<>();

    /** 商品详情（JSON 结构/富文本） */
    @Size(max = 20000, message = "商品详情最长 20000 字符")
    private String detailJson;

    /** SPU 属性键值 JSON（JSON 对象串，按属性主数据校验，未收录/违规仅告警） */
    @Size(max = 20000, message = "属性快照最长 20000 字符")
    private String attrsJson;

    /** SKU 列表（至少一个，最多 100 个） */
    @NotEmpty(message = "至少需要一个 SKU")
    @Size(min = 1, max = 100, message = "SKU 数量必须在 1-100 之间")
    @Valid
    private List<SkuSaveRequest> skus = new ArrayList<>();
}
