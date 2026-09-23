package com.shop.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 黑盒 HTTP 客户端：基于 JDK HttpClient，不依赖任何业务服务的 Spring 上下文。
 *
 * <p>每个实例持有一个登录态 token（可匿名）；统一封装 JSON 序列化、Bearer 头、
 * Result 包装解析（{@code code/message/data}）与常用断言。</p>
 */
public class ApiClient {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private volatile String token;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public static ApiClient create() {
        return new ApiClient(System.getProperty("shop.gateway", "http://localhost:8080"));
    }

    public ApiClient withToken(String token) {
        this.token = token;
        return this;
    }

    public String getToken() {
        return token;
    }

    // ------------------------------------------------------------------
    // JSON 构造帮助
    // ------------------------------------------------------------------

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ObjectNode obj(String k1, Object v1) {
        ObjectNode o = obj();
        put(o, k1, v1);
        return o;
    }

    public static ObjectNode obj(String k1, Object v1, String k2, Object v2) {
        ObjectNode o = obj(k1, v1);
        put(o, k2, v2);
        return o;
    }

    public static ObjectNode obj(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        ObjectNode o = obj(k1, v1, k2, v2);
        put(o, k3, v3);
        return o;
    }

    /** 任意偶数个 key/value 的可变参数重载（固定长度重载优先匹配，不会产生歧义）。 */
    public static ObjectNode obj(Object... kv) {
        ObjectNode o = obj();
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("obj() 参数必须成对出现");
        }
        for (int i = 0; i < kv.length; i += 2) {
            put(o, kv[i].toString(), kv[i + 1]);
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    public static void put(ObjectNode node, String field, Object value) {
        if (value == null) {
            node.putNull(field);
        } else if (value instanceof JsonNode jn) {
            node.set(field, jn);
        } else if (value instanceof String s) {
            node.put(field, s);
        } else if (value instanceof Integer i) {
            node.put(field, i);
        } else if (value instanceof Long l) {
            node.put(field, l);
        } else if (value instanceof Boolean b) {
            node.put(field, b);
        } else if (value instanceof Double d) {
            node.put(field, d);
        } else if (value instanceof Map<?, ?> m) {
            ObjectNode child = obj();
            m.forEach((k, v) -> put(child, k.toString(), v));
            node.set(field, child);
        } else {
            node.putPOJO(field, value);
        }
    }

    // ------------------------------------------------------------------
    // 低层 HTTP
    // ------------------------------------------------------------------

    public Raw get(String path) {
        return request("GET", path, null);
    }

    public Raw get(String path, Map<String, ?> query) {
        return request("GET", path + "?" + buildQuery(query), null);
    }

    public Raw post(String path, Object body) {
        return request("POST", path, body);
    }

    public Raw put(String path, Object body) {
        return request("PUT", path, body);
    }

    public Raw delete(String path, Object body) {
        return request("DELETE", path, body);
    }

    public Raw delete(String path) {
        return request("DELETE", path, null);
    }

    /** 发送原始请求（path 可携带未经二次编码的 %XX 序列，用于绕过变体测试）。 */
    public Raw rawRequest(String method, String rawPath, Object body) {
        return doRequest(method, rawPath, body, null);
    }

    /** 发送原始请求并附加自定义请求头（用于伪造身份头被网关清洗等测试）。 */
    public Raw rawRequest(String method, String rawPath, Object body, Map<String, String> extraHeaders) {
        return doRequest(method, rawPath, body, extraHeaders);
    }

    private Raw request(String method, String path, Object body) {
        return doRequest(method, path, body, null);
    }

    private Raw doRequest(String method, String pathOrUrl, Object body, Map<String, String> extraHeaders) {
        try {
            String url = pathOrUrl.startsWith("http") ? pathOrUrl : baseUrl + pathOrUrl;
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));
            if (token != null && !token.isBlank()) {
                b.header("Authorization", "Bearer " + token);
            }
            b.header("Content-Type", "application/json");
            // 业务测试需要验证网关对伪造身份头的清洗行为
            b.header("X-Trace-Id", "e2e-" + System.nanoTime());
            if (extraHeaders != null) {
                extraHeaders.forEach(b::header);
            }
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8);
            b.method(method, publisher);
            HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Raw(resp.statusCode(), resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 调用失败 " + method + " " + pathOrUrl + ": " + e.getMessage(), e);
        }
    }

    private String buildQuery(Map<String, ?> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        query.forEach((k, v) -> {
            if (v != null) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8));
            }
        });
        return sb.toString();
    }

    static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ------------------------------------------------------------------
    // 语义化快捷方法
    // ------------------------------------------------------------------

    /** 断言业务成功（code=0）并返回 data 节点。 */
    public JsonNode okData(Raw raw, String scene) {
        if (raw.bizCode() != 0) {
            throw new AssertionError(scene + " 期望成功(code=0)，实际 http=" + raw.httpStatus
                    + " body=" + raw.text);
        }
        return raw.json() == null ? null : raw.json().path("data");
    }

    /** 断言业务成功并返回 Long 型 data。 */
    public long okLong(Raw raw, String scene) {
        return okData(raw, scene).asLong();
    }

    public Raw mustPost(String path, Object body, String scene) {
        Raw raw = post(path, body);
        if (raw.bizCode() != 0) {
            throw new AssertionError(scene + " 期望成功，实际 http=" + raw.httpStatus + " body=" + raw.text);
        }
        return raw;
    }

    /** 断言业务成功的 PUT。 */
    public Raw mustPut(String path, Object body, String scene) {
        Raw raw = put(path, body);
        if (raw.bizCode() != 0) {
            throw new AssertionError(scene + " 期望成功，实际 http=" + raw.httpStatus + " body=" + raw.text);
        }
        return raw;
    }

    public static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** HTTP 响应包装。 */
    public static final class Raw {
        public final int httpStatus;
        public final String text;

        Raw(int httpStatus, String text) {
            this.httpStatus = httpStatus;
            this.text = text == null ? "" : text;
        }

        public JsonNode json() {
            if (text.isBlank()) {
                return null;
            }
            try {
                return MAPPER.readTree(text);
            } catch (Exception e) {
                return null;
            }
        }

        /** 业务码：无法解析时为 Integer.MIN_VALUE（如网关 401/404 的特殊 code 也可显式读取）。 */
        public int bizCode() {
            JsonNode j = json();
            return j != null && j.has("code") ? j.get("code").asInt() : Integer.MIN_VALUE;
        }

        public JsonNode data() {
            JsonNode j = json();
            return j == null ? null : j.path("data");
        }

        public String message() {
            JsonNode j = json();
            return j != null && j.has("message") ? j.get("message").asText() : text;
        }
    }
}
