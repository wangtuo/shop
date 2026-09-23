package com.shop.framework.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import feign.Response;
import feign.codec.ErrorDecoder;

/**
 * 统一 Feign 错误解码（C13），仅处理 HTTP 非 2xx：
 * <ul>
 *   <li>5xx / 空 body / body 非 Result 结构 / 其余 4xx →
 *       {@code BizException(DEPENDENCY_FAIL, "下游服务不可用: "+clientName)}，
 *       可恢复，进 MQ 重试集合；</li>
 *   <li>body 为 {@code Result{code!=0}}（含 404）→ 原样透传业务 code/message，业务码不被吞；</li>
 *   <li>404 且 body 无业务码 → {@link ErrorCode#NOT_FOUND}。</li>
 * </ul>
 * IO 异常 / RetryableException 在 {@link Resilience4jCapability} 归一，不走本类。
 * message 不含响应体、IP、端口、堆栈。
 */
public class ShopErrorDecoder implements ErrorDecoder {

    private final ObjectMapper objectMapper;

    public ShopErrorDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        String clientName = FeignClientNames.fromRequest(response.request());
        byte[] body = FeignBodies.readBody(response);
        JsonNode node = FeignBodies.parseTree(objectMapper, body);
        int status = response.status();

        if (status >= 500) {
            // 5xx 一律依赖失败（即使 body 携带 Result，也以上游不可用语义为准）
            return new BizException(ErrorCode.DEPENDENCY_FAIL, "下游服务不可用: " + clientName);
        }

        if (FeignBodies.isResult(node) && FeignBodies.code(node) != ErrorCode.SUCCESS.getCode()) {
            // 4xx body 携带 Result 业务码时透传（404 透传 body.code 优先）
            return FeignResults.businessException(FeignBodies.code(node), FeignBodies.message(node));
        }

        if (status == 404) {
            return new BizException(ErrorCode.NOT_FOUND, "下游资源不存在: " + clientName);
        }

        // 空体 / 非 Result 结构 / 其余 4xx 统一依赖失败（可恢复）
        return new BizException(ErrorCode.DEPENDENCY_FAIL, "下游服务不可用: " + clientName);
    }
}
