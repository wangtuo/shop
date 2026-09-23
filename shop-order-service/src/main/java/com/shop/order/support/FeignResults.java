package com.shop.order.support;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;

import java.util.Arrays;

/**
 * Feign 调用结果解包：跨域调用失败一律转 {@link BizException}，由订单侧决定回滚/补偿。
 * 不吞异常、不把失败结果当成功。
 */
public final class FeignResults {

    private FeignResults() {
    }

    public static <T> T unwrap(Result<T> result) {
        if (result == null) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL, "依赖服务返回为空");
        }
        if (result.isSuccess()) {
            return result.getData();
        }
        throw new BizException(resolve(result.getCode()), result.getMessage());
    }

    /** 远端错误码能对应上本地枚举则透传（如库存/积分不足），否则归为依赖失败。 */
    private static ErrorCode resolve(int code) {
        return Arrays.stream(ErrorCode.values())
                .filter(e -> e.getCode() == code)
                .findFirst()
                .orElse(ErrorCode.DEPENDENCY_FAIL);
    }
}
