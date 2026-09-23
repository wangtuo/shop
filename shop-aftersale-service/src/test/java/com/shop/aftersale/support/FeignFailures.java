package com.shop.aftersale.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.framework.feign.ShopErrorDecoder;
import feign.Request;
import feign.Response;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 测试夹具：用框架 {@link ShopErrorDecoder} 真实解码 HTTP 500，
 * 钉死「5xx → BizException(DEPENDENCY_FAIL) 可恢复重试」语义（R-B6）。
 */
public final class FeignFailures {

    private static final ShopErrorDecoder DECODER = new ShopErrorDecoder(new ObjectMapper());

    private FeignFailures() {
    }

    /** 框架 ErrorDecoder 对下游 500（空体）解码出的异常——业务调用点据此回滚 + broker 重投。 */
    public static Exception serverError500(String methodKey) {
        Request request = Request.create(Request.HttpMethod.GET, "/test",
                Map.of(), new byte[0], StandardCharsets.UTF_8);
        Response response = Response.builder()
                .status(500).reason("Internal Server Error")
                .request(request)
                .body(new byte[0])
                .headers(Map.of())
                .build();
        return DECODER.decode(methodKey, response);
    }
}
