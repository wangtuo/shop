package com.shop.framework.feign;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import feign.Request;
import feign.Request.HttpMethod;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * C13/R-B2 六类错误形态 + FeignResults 三用例。纯单测，不起 Spring 上下文、无 AOP 依赖。
 */
class ShopErrorDecoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ShopErrorDecoder errorDecoder = new ShopErrorDecoder(objectMapper);
    private final ShopResultDecoder resultDecoder = new ShopResultDecoder(objectMapper);

    private static final Type RESULT_STRING = new TypeReference<Result<String>>() {
    }.getType();
    private static final Type RESULT_VOID = new TypeReference<Result<Void>>() {
    }.getType();

    // ---------- ShopErrorDecoder：5xx / 404 ----------

    @Test
    void http500_htmlBody_mapsToDependencyFailWithoutLeakingBody() {
        Response response = response(500, "<html><body>Internal error at /10.244.0.114:8084</body></html>");

        BizException ex = assertThrows(BizException.class, () -> {
            throw errorDecoder.decode("PayClient#pay", response);
        });

        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
        assertEquals("下游服务不可用: pay-service", ex.getMessage());
        assertFalse(ex.getMessage().contains("10.244.0.114"));
        assertFalse(ex.getMessage().contains("<html"));
    }

    @Test
    void http500_resultBodyStillMapsToDependencyFail() {
        Response response = response(500, "{\"code\":50001,\"message\":\"订单不存在\",\"data\":null}");

        BizException ex = assertThrows(BizException.class, () -> {
            throw errorDecoder.decode("PayClient#pay", response);
        });

        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
    }

    @Test
    void http404_emptyBody_mapsToNotFound() {
        Response response = response(404, null);

        BizException ex = assertThrows(BizException.class, () -> {
            throw errorDecoder.decode("OrderClient#get", response);
        });

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void http404_resultBodyCodeTakesPrecedence() {
        Response response = response(404,
                "{\"code\":50001,\"message\":\"订单不存在\",\"data\":null}");

        BizException ex = assertThrows(BizException.class, () -> {
            throw errorDecoder.decode("OrderClient#get", response);
        });

        assertEquals(50001, ex.getCode());
        assertEquals("订单不存在", ex.getMessage());
    }

    @Test
    void http400_resultBodyCodePassesThrough() {
        Response response = response(400,
                "{\"code\":30001,\"message\":\"库存不足\",\"data\":null}");

        BizException ex = assertThrows(BizException.class, () -> {
            throw errorDecoder.decode("ProductClient#deduct", response);
        });

        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), ex.getCode());
        assertEquals("库存不足", ex.getMessage());
    }

    // ---------- ShopResultDecoder：空体 / 非 Result / 业务码 / data-null ----------

    @Test
    void http200_emptyBody_mapsToDependencyFail() {
        BizException ex = assertThrows(BizException.class,
                () -> resultDecoder.decode(response(200, ""), RESULT_STRING));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
    }

    @Test
    void http200_nonResultStructure_mapsToDependencyFail() {
        BizException html = assertThrows(BizException.class,
                () -> resultDecoder.decode(response(200, "<html>bad gateway</html>"), RESULT_STRING));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), html.getCode());

        BizException plainObject = assertThrows(BizException.class,
                () -> resultDecoder.decode(response(200, "{\"foo\":\"bar\"}"), RESULT_STRING));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), plainObject.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void http200_businessCodePassesThrough() {
        Response response = response(200,
                "{\"code\":30001,\"message\":\"库存不足\",\"data\":null}");

        BizException ex = assertThrows(BizException.class,
                () -> resultDecoder.decode(response, RESULT_STRING));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), ex.getCode());
        assertEquals("库存不足", ex.getMessage());
    }

    @Test
    void http200_successButDataNull_mapsToDependencyFailForNonWrapperGeneric() {
        Response response = response(200,
                "{\"code\":0,\"message\":\"成功\",\"data\":null}");

        BizException ex = assertThrows(BizException.class,
                () -> resultDecoder.decode(response, RESULT_STRING));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
    }

    @Test
    void http200_successDataNullAllowedForVoidGeneric() throws Exception {
        Response response = response(200,
                "{\"code\":0,\"message\":\"成功\",\"data\":null}");

        Object decoded = resultDecoder.decode(response, RESULT_VOID);
        Result<?> result = assertInstanceOf(Result.class, decoded);
        assertEquals(0, result.getCode());
    }

    @Test
    void http200_successReturnsResult() throws Exception {
        Response response = response(200,
                "{\"code\":0,\"message\":\"成功\",\"data\":\"pay-url\"}");

        Object decoded = resultDecoder.decode(response, RESULT_STRING);
        assertEquals("pay-url", ((Result<?>) decoded).getData());
    }

    // ---------- FeignResults：成功 / 失败 / null ----------

    @Test
    void unwrap_success_returnsData() {
        assertEquals("ok", FeignResults.unwrap(Result.success("ok")));
    }

    @Test
    void unwrap_businessFailure_passesCodeThrough() {
        BizException ex = assertThrows(BizException.class,
                () -> FeignResults.unwrap(Result.fail(ErrorCode.STOCK_NOT_ENOUGH, "库存不足")));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), ex.getCode());
        assertEquals("库存不足", ex.getMessage());
    }

    @Test
    void unwrap_null_mapsToDependencyFail() {
        BizException ex = assertThrows(BizException.class, () -> FeignResults.unwrap(null));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
    }

    // ---------- fixtures ----------

    private static Response response(int status, String body) {
        Request request = Request.create(
                HttpMethod.GET,
                "http://pay-service/api/x",
                Map.of(),
                null,
                null,
                new feign.RequestTemplate().method(HttpMethod.GET).target("http://pay-service"));
        Response.Builder builder = Response.builder().status(status).request(request);
        if (body != null) {
            builder.body(body, StandardCharsets.UTF_8);
        }
        return builder.build();
    }
}
