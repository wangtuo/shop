package com.shop.framework.feign;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;

import java.util.Arrays;

/**
 * Feign 调用结果统一解包（C13）：合并原 shop-order/shop-pay 两份逐字重复实现，
 * 业务波统一切到本类。
 *
 * <p>{@link ShopErrorDecoder} 与框架 Decoder 落地后，{@code Result{code!=0}}、
 * {@code data=null}、下游 5xx 等异常已在框架层直接抛 {@link BizException}，
 * 本方法仅兼容保留；新代码可直接使用 Feign 返回值（框架保证非 null）。
 */
public final class FeignResults {

    private FeignResults() {
    }

    /**
     * 解包 Result：null / 非 success 一律转 BizException，不吞异常、不把失败当成功。
     * 远端错误码能对应本地枚举则原样透传（如库存/积分不足），否则归为依赖失败。
     */
    public static <T> T unwrap(Result<T> result) {
        if (result == null) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL, "依赖服务返回为空");
        }
        if (result.isSuccess()) {
            return result.getData();
        }
        throw businessException(result.getCode(), result.getMessage());
    }

    /** 框架内部统一的业务码透传构造：message 缺失时回落枚举文案。 */
    static BizException businessException(int code, String message) {
        ErrorCode errorCode = resolve(code);
        if (message == null || message.isBlank()) {
            return new BizException(errorCode, errorCode.getMessage());
        }
        return new BizException(errorCode, message);
    }

    private static ErrorCode resolve(int code) {
        return Arrays.stream(ErrorCode.values())
                .filter(e -> e.getCode() == code)
                .findFirst()
                .orElse(ErrorCode.DEPENDENCY_FAIL);
    }
}
