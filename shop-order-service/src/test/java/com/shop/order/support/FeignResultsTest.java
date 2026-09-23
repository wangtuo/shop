package com.shop.order.support;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feign 结果解包单测：成功取数据、空结果依赖失败、远端码值映射、未知码归依赖失败。
 */
class FeignResultsTest {

    @Test
    void unwrap_success_returnsData() {
        String data = FeignResults.unwrap(Result.success("ok"));
        assertThat(data).isEqualTo("ok");
        Object empty = FeignResults.unwrap(Result.success());
        assertThat(empty).isNull();
    }

    @Test
    void unwrap_nullResult_throwsDependencyFail() {
        assertThatThrownBy(() -> FeignResults.unwrap(null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.DEPENDENCY_FAIL.getCode());
    }

    @Test
    void unwrap_knownRemoteCode_mapsToLocalEnum() {
        assertThatThrownBy(() -> FeignResults.unwrap(
                Result.fail(ErrorCode.STOCK_NOT_ENOUGH, "库存不足")))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.STOCK_NOT_ENOUGH.getCode());
    }

    @Test
    void unwrap_failResult_throwsWithMessage() {
        assertThatThrownBy(() -> FeignResults.unwrap(Result.fail(999999, "远端神秘错误")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("远端神秘错误");
    }
}
