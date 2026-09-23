package com.shop.framework.jackson;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shop.common.jackson.JsonViews;
import com.shop.common.jackson.PhoneMaskingSerializer;
import lombok.Data;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手机号脱敏序列化器单测（M-1）：
 * 默认视图脱敏；Internal 视图（内部 Feign /inner/** 回查）输出完整手机号。
 */
class PhoneMaskingSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    static class UserView {
        @JsonSerialize(using = PhoneMaskingSerializer.class)
        @JsonView(JsonViews.Internal.class)
        private String phone;

        UserView(String phone) {
            this.phone = phone;
        }
    }

    @Test
    void 对外默认序列化_手机号保留前3后4() throws Exception {
        String json = objectMapper.writeValueAsString(new UserView("13812345678"));
        assertTrue(json.contains("138****5678"), json);
    }

    @Test
    void 内部视图序列化_输出完整手机号() throws Exception {
        String json = objectMapper.writerWithView(JsonViews.Internal.class)
                .writeValueAsString(new UserView("13812345678"));
        assertTrue(json.contains("13812345678"), json);
    }

    @Test
    void 短号与null_原样处理() {
        assertEquals("12345", PhoneMaskingSerializer.mask("12345"));
        assertEquals(null, PhoneMaskingSerializer.mask(null));
    }
}
