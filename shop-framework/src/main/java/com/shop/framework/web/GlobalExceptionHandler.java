package com.shop.framework.web;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：业务异常透出明确错误码，参数/请求格式类错误统一 HTTP 400 + body.code=10001（API-P/C18），
 * 其余异常统一 500 兜底，避免堆栈/类名/方法签名外泄。
 *
 * <p><b>E-3 冻结契约</b>：{@link BizException} 维持 HTTP 200（401/403 除外）+ body.code 结构不变，
 * 全链路、shop-e2e、前端均依赖 body.code 判定。</p>
 *
 * <p>请求体大小配套键：{@code shop.web.body.max-request-size}（业务侧覆盖口径，默认与
 * {@code spring.servlet.multipart.max-file-size=10MB / max-request-size=20MB} 对齐）；
 * 容器侧 Tomcat maxSwallowSize/maxPostSize 基线不强制改写，超限统一在本类映射为 400+10001。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** O6：业务异常按 code 计数；字段注入可空，无注册表环境（单测）空转。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shop.framework.metrics.BizMetrics bizMetrics;

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, HttpServletResponse response) {
        if (e.getCode() == ErrorCode.UNAUTHORIZED.getCode()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        } else if (e.getCode() == ErrorCode.FORBIDDEN.getCode()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
        }
        if (bizMetrics != null) {
            bizMetrics.bizException(e.getCode());
        }
        // 依赖失败/超时必须带 cause 全链打印：这类异常的根因（LB 无实例、NIO 死通道、
        // 连接拒绝）只存在于 cause 栈里，仅打 message 会把跨服务故障变成不可定位的 10008。
        if (e.getCode() == ErrorCode.DEPENDENCY_FAIL.getCode()
                || e.getCode() == ErrorCode.DEPENDENCY_TIMEOUT.getCode()) {
            log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage(), e);
        } else {
            log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        }
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValid(BindException e, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ErrorCode.PARAM_INVALID, msg);
    }

    /**
     * Bean Validation（@RequestParam/@PathVariable 类级 @Validated 触发）：
     * 只回校验模板文本与属性路径末段（属性名），禁止 e.getMessage()（含 类名.方法名.arg0 签名）。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        String msg = e.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::formatViolation)
                .collect(Collectors.joining("; "));
        return Result.fail(ErrorCode.PARAM_INVALID, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(ErrorCode.PARAM_INVALID, "缺少参数：" + e.getParameterName());
    }

    /** 坏 JSON / 反序列化失败：固定文案，不回显 Jackson 原始 message（可能含类名/字段类型内部结构）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e, HttpServletResponse response) {
        log.warn("请求体不可读: {}", e.getMessage());
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(ErrorCode.PARAM_INVALID, "请求体格式错误");
    }

    /** 路径变量/参数类型不匹配：只带参数名，不回显 requiredType 与原始 value。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(ErrorCode.PARAM_INVALID, "参数类型错误: " + e.getName());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<Void> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(ErrorCode.PARAM_INVALID, "不支持的媒体类型");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public Result<Void> handleMissingPart(MissingServletRequestPartException e, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(ErrorCode.PARAM_INVALID, "缺少请求片段：" + e.getRequestPartName());
    }

    /** multipart 单文件/整请求超限（MaxUploadSizeExceededException 是 MultipartException 子类，优先匹配）。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadSizeExceeded(MaxUploadSizeExceededException e, HttpServletResponse response) {
        log.warn("上传内容超过大小限制");
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(ErrorCode.PARAM_INVALID, "上传内容超过大小限制");
    }

    /** multipart 解析失败（边界异常/流损坏等）：固定文案，不回显容器细节。 */
    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipart(MultipartException e, HttpServletResponse response) {
        log.warn("multipart 请求解析失败: {}", e.getMessage());
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(ErrorCode.PARAM_INVALID, "请求解析失败");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return Result.fail(ErrorCode.PARAM_INVALID, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    /**
     * 取约束路径末段属性名；形如 methodName.arg0.points 只保留 points，
     * 末段为 argN（未编译 -parameters）时不附带路径，仅回校验模板文本。
     */
    private static String formatViolation(ConstraintViolation<?> violation) {
        String leafName = null;
        for (Path.Node node : violation.getPropertyPath()) {
            leafName = node.getName();
        }
        String message = violation.getMessage();
        if (leafName == null || leafName.isBlank() || leafName.matches("arg\\d+")) {
            return message;
        }
        return leafName + ": " + message;
    }
}
