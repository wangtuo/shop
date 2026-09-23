package com.shop.framework.web;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * API-P：四类参数异常 + 超限异常断言 HTTP 400 + body.code=10001，
 * 且响应消息只含模板文本/参数名，不泄露类名、方法签名、requiredType、原始 value；
 * BizException 普通业务码保持 HTTP 200 + body.code（E-3 冻结）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unreadable_body_returns_400_with_fixed_message_and_no_class_name() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Cannot construct instance of com.shop.internal.SecretPayload",
                (org.springframework.http.HttpInputMessage) null);

        Result<Void> result = handler.handleNotReadable(ex, response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(result.getMessage()).isEqualTo("请求体格式错误");
        assertThat(result.getMessage()).doesNotContain("com.shop", "SecretPayload");
    }

    @Test
    void type_mismatch_returns_400_with_param_name_only() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("abc", Long.class, "orderId", null,
                        new NumberFormatException("For input string: \"abc\""));

        Result<Void> result = handler.handleTypeMismatch(ex, response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(result.getCode()).isEqualTo(10001);
        assertThat(result.getMessage()).isEqualTo("参数类型错误: orderId");
        assertThat(result.getMessage()).doesNotContain("Long", "abc", "NumberFormatException");
    }

    @Test
    @SuppressWarnings("unchecked")
    void constraint_violation_hides_method_signature() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        Path.Node node = mock(Path.Node.class);
        when(node.getName()).thenReturn("points");
        Path path = mock(Path.class);
        when(path.iterator()).thenReturn(java.util.List.of(node).iterator());
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("必须为正数");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        Result<Void> result = handler.handleConstraint(ex, response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(result.getCode()).isEqualTo(10001);
        assertThat(result.getMessage()).isEqualTo("points: 必须为正数");
        assertThat(result.getMessage()).doesNotContain("arg0", "lockStock", "com.shop");
    }

    @Test
    void missing_param_returns_400() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("points", "Integer");

        Result<Void> result = handler.handleMissingParam(ex, response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(result.getCode()).isEqualTo(10001);
        assertThat(result.getMessage()).contains("points");
    }

    @Test
    void upload_size_exceeded_returns_400() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MaxUploadSizeExceededException ex =
                new MaxUploadSizeExceededException(10485760L, new IllegalStateException("cliff"));

        Result<Void> result = handler.handleUploadSizeExceeded(ex, response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(result.getCode()).isEqualTo(10001);
        assertThat(result.getMessage()).isEqualTo("上传内容超过大小限制");
    }

    @Test
    void biz_exception_keeps_http_200_and_body_code() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        BizException ex = new BizException(ErrorCode.PARAM_INVALID, "业务侧自定义参数错误");

        Result<Void> result = handler.handleBiz(ex, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(result.getCode()).isEqualTo(10001);
        assertThat(result.getMessage()).isEqualTo("业务侧自定义参数错误");
    }
}
