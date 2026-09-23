package com.shop.common.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.io.IOException;

/**
 * 手机号脱敏 Jackson 序列化器：保留前 3 位与后 4 位，如 {@code 13812345678 -> 138****5678}。
 *
 * <p>当序列化活动视图为 {@link JsonViews.Internal}（内部 Feign /inner/** 回查）时，
 * 退化为普通字符串序列化，输出完整手机号；外部网关流量无该视图，一律脱敏。
 *
 * <p>放在 shop-common 而非 shop-framework：被脱敏的 DTO（UserDTO/ReceiverDTO/OrderDTO）
 * 位于 shop-api，shop-api 只依赖 shop-common。
 */
public class PhoneMaskingSerializer extends StdSerializer<String> implements ContextualSerializer {

    public PhoneMaskingSerializer() {
        super(String.class);
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeString(mask(value));
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) {
        Class<?> activeView = prov.getActiveView();
        if (activeView != null && JsonViews.Internal.class.isAssignableFrom(activeView)) {
            // 内部视图：明文输出
            return ToStringSerializer.instance;
        }
        return this;
    }

    /**
     * 手机号脱敏：保留前 3 后 4；长度不足 7 位原样返回，null 原样返回（序列化器不会收到 null）。
     */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        int len = phone.length();
        return phone.substring(0, 3) + "*".repeat(len - 7) + phone.substring(len - 4);
    }
}
