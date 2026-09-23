package com.shop.common.validation;

/**
 * 媒体 URL 校验共享常量（TRADE C36 的 {@code @Pattern + @Size} 组合常量形态）。
 *
 * <p>shop-common 不依赖 jakarta.validation-api，故此处只提供编译期常量，
 * 由使用方（如 product 域 SpuSaveRequest）按下述写法声明校验：
 * <pre>
 * // 单条
 * &#64;NotBlank
 * &#64;Pattern(regexp = MediaUrls.URL_PATTERN, message = "图片地址必须为 http/https URL")
 * &#64;Size(max = MediaUrls.URL_MAX_LENGTH, message = "图片地址长度不能超过 512")
 * private String mainImage;
 *
 * // 列表：字段 &#64;Size(max = MediaUrls.LIST_MAX)，元素 &#64;Pattern(regexp = MediaUrls.URL_PATTERN)
 * &#64;Valid
 * &#64;Size(max = MediaUrls.LIST_MAX, message = "图片数量不能超过 10")
 * private List&lt;&#64;Pattern(regexp = MediaUrls.URL_PATTERN) String&gt; images;
 * </pre>
 */
public final class MediaUrls {

    /** 仅允许 http/https 协议 URL */
    public static final String URL_PATTERN = "^https?://\\S+$";

    /** 单条媒体 URL 最大长度 */
    public static final int URL_MAX_LENGTH = 512;

    /** 媒体 URL 列表最大条数 */
    public static final int LIST_MAX = 10;

    private MediaUrls() {
    }
}
