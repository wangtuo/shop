package com.shop.gateway.error;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关统一错误包裹体（C14）：与 shop-common {@code Result} 同构的三字段
 * {@code {"code","message","data"}}，但不输出 timestamp（网关错误契约固定三字段）。
 * message 只允许使用预置安全文案，禁止拼接异常原文（可能含 IP/端口/Netty 堆栈）。
 */
public final class GatewayErrorBody {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private GatewayErrorBody() {
    }

    public static byte[] envelope(int code, String message) {
        // LinkedHashMap 保证字段顺序 code/message/data；data 显式 null。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            return ("{\"code\":" + code + ",\"message\":\"系统繁忙，请稍后再试\",\"data\":null}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
