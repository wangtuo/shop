package com.shop.framework.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.Util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * ErrorDecoder / Decoder 共用的响应体离线读取与 Result 结构识别。
 * 只读一次字节流，任何异常均降级为空体，绝不把 body 原文/IP/堆栈拼进异常文案。
 */
final class FeignBodies {

    private FeignBodies() {
    }

    static byte[] readBody(Response response) {
        if (response == null || response.body() == null) {
            return new byte[0];
        }
        try (InputStream in = response.body().asInputStream()) {
            return Util.toByteArray(in);
        } catch (IOException | RuntimeException e) {
            return new byte[0];
        }
    }

    static JsonNode parseTree(ObjectMapper objectMapper, byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 是否为 {@code {"code":数字,...}} 的 Result 同构体。 */
    static boolean isResult(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode code = node.get("code");
        return code != null && code.isNumber();
    }

    static int code(JsonNode node) {
        return node.path("code").asInt();
    }

    static String message(JsonNode node) {
        JsonNode message = node.get("message");
        return message == null || message.isNull() ? null : message.asText();
    }

    static Charset charset(Response response) {
        Charset charset = response.charset();
        return charset != null ? charset : StandardCharsets.UTF_8;
    }
}
