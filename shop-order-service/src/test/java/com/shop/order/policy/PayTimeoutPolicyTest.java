package com.shop.order.policy;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付超时矩阵单测（design 5.3.3）：
 * 普通/换货 1800s、秒杀 900s、拼团 86400s、预售尾款 3 天 259200s。
 */
class PayTimeoutPolicyTest {

    private final PayTimeoutPolicy policy = new PayTimeoutPolicy();

    @Test
    void expireSeconds_matrixByOrderType() {
        assertThat(policy.expirePaySeconds(1)).isEqualTo(1800L);
        assertThat(policy.expirePaySeconds(5)).isEqualTo(1800L);
        assertThat(policy.expirePaySeconds(2)).isEqualTo(900L);
        assertThat(policy.expirePaySeconds(3)).isEqualTo(86400L);
        assertThat(policy.expirePaySeconds(4)).isEqualTo(259200L);
    }

    @Test
    void expireTime_addsMatrixSeconds() {
        LocalDateTime now = LocalDateTime.of(2026, 3, 15, 10, 0, 0);
        assertThat(policy.expireTime(2, now))
                .isEqualTo(LocalDateTime.of(2026, 3, 15, 10, 15, 0));
        assertThat(policy.expireTime(3, now))
                .isEqualTo(LocalDateTime.of(2026, 3, 16, 10, 0, 0));
        assertThat(policy.expireTime(4, now))
                .isEqualTo(LocalDateTime.of(2026, 3, 18, 10, 0, 0));
    }

    @Test
    void expireSeconds_nullType_throwsParamInvalid() {
        assertThatThrownBy(() -> policy.expirePaySeconds(null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    void expireSeconds_unknownType_throwsParamInvalid() {
        assertThatThrownBy(() -> policy.expirePaySeconds(99))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    void expireSeconds_zeroAndSix_throwParamInvalid() {
        // API-T 勘误现状钉板：0/6 等未知类型在 PayTimeoutPolicy 前置抛 PARAM_INVALID
        for (Integer t : new Integer[]{0, 6}) {
            assertThatThrownBy(() -> policy.expirePaySeconds(t))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getCode())
                    .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        }
    }

    @Test
    void groupSuccessExtendSeconds_is30Min_and24hBranchUnchanged() {
        // B1：成团追加窗口 1800s；拼团开单仍为 24h，不改既有分支
        assertThat(policy.groupSuccessExtendSeconds()).isEqualTo(1800L);
        assertThat(policy.expirePaySeconds(3)).isEqualTo(86400L);
    }
}
