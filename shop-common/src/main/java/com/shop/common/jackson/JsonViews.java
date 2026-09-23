package com.shop.common.jackson;

/**
 * Jackson 序列化视图标记。
 *
 * <p>敏感字段（如手机号）在默认对外视图中走脱敏序列化器；标注了 {@link Internal} 视图的字段
 * 仅在内部服务间调用（{@code /inner/**} Controller 方法上声明 {@code @JsonView(JsonViews.Internal.class)}）
 * 时输出明文，保证 Feign 回查拿到完整数据。
 */
public final class JsonViews {

    private JsonViews() {
    }

    /** 内部服务间视图：Feign 调用 {@code /inner/**} 时使用，输出未脱敏字段。 */
    public interface Internal {
    }
}
