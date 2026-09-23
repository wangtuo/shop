package com.shop.pay.channel.adapter.real;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 真实渠道响应解析与错误码映射（B8 骨架）。
 *
 * <p>TODO(真实渠道联调) 边界：微信 v3 JSON（HTTP 200/204 + 业务 code）、支付宝 form/JSON
 * （sub_code/sub_msg）、银行/云闪付 XML/JSON 各自的错误码表，需在联调时补齐到本地
 * 受理码（10 受理中 / 20 成功 / 30 失败）的映射与可重试判定；当前未联调方法一律抛
 * {@link ErrorCode#DEPENDENCY_FAIL}，禁止伪成功。</p>
 */
@Component
public class RealResponseParser {

    /** 解析下单响应：取 channelOrderNo / payUrl / 错误信息。 */
    public CreateOrderPayload parseCreateOrder(String channelCode, String raw) {
        throw notWired(channelCode, "解析下单响应");
    }

    /** 解析支付查询响应。 */
    public QueryPayload parseQuery(String channelCode, String raw) {
        throw notWired(channelCode, "解析支付查询响应");
    }

    /** 解析退款响应：取渠道退款号 / 受理状态 / 失败原因。 */
    public RefundPayload parseRefund(String channelCode, String raw) {
        throw notWired(channelCode, "解析退款响应");
    }

    /** 解析退款查询响应。 */
    public RefundPayload parseQueryRefund(String channelCode, String raw) {
        throw notWired(channelCode, "解析退款查询响应");
    }

    private BizException notWired(String channelCode, String ability) {
        return new BizException(ErrorCode.DEPENDENCY_FAIL, "真实渠道 " + channelCode + " " + ability + "能力未联调");
    }

    /** 下单响应归一化结果。 */
    public record CreateOrderPayload(String channelOrderNo, String payUrl) {
    }

    /** 支付查询归一化结果：state PAYING/SUCCESS/FAIL/CLOSED。 */
    public record QueryPayload(String state, String channelTxnNo) {
    }

    /** 退款/退款查询归一化结果：10 受理中 20 成功 30 失败。 */
    public record RefundPayload(int status, String channelRefundNo, String failReason) {
    }
}
