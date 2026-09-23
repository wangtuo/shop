package com.shop.framework.mq;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;

import java.util.Set;

/**
 * MQ 消费失败分类（P2-3）。
 *
 * <p>RocketMQ 推送消费对任何异常都无限重投，毒丸消息（永远不可能靠重试成功的业务终态错误）
 * 会长期占住消费线程、刷爆告警。此处区分：
 * <ul>
 *   <li><b>不可恢复（终态）</b>：参数/鉴权/签名/金额类 4xx 语义错误 —— ACK 丢弃，
 *       由 error 日志 + 对账/人工核对兜底，不再重投；</li>
 *   <li><b>可恢复</b>：冲突、依赖故障、系统错误、余额/库存不足（等待补偿/充值后可能成立）等
 *       —— 返回 FAILURE 由 broker 重试。</li>
 * </ul>
 *
 * <p><b>NOT_FOUND / ORDER_NOT_FOUND 不归为终态</b>：跨服务事件消费里「引用聚合暂时查不到」
 * 并不少见——本端事务可见性/只读库复制延迟、事件与补数任务竞态、上游事务尚未提交即发出
 * （异常链路）等。若直接 ACK 丢弃会造成库存 confirm/清算登记等副作用永久丢失；改为返回
 * FAILURE 交 broker 按退避重试，超过 retryMaxTimes(16) 后消息进入 %DLQ% 死信队列，
 * 由对账/告警人工兜底（真·毒丸在 DLQ 可见，而不是静默蒸发）。
 */
public final class MqErrorPolicy {

    /** 重试无意义的终态业务错误码。 */
    private static final Set<Integer> TERMINAL_CODES = Set.of(
            ErrorCode.PARAM_INVALID.getCode(),
            ErrorCode.UNAUTHORIZED.getCode(),
            ErrorCode.FORBIDDEN.getCode(),
            ErrorCode.PAY_SIGN_ERROR.getCode(),
            ErrorCode.REFUND_AMOUNT_ERROR.getCode(),
            ErrorCode.SETTLE_AMOUNT_ERROR.getCode(),
            ErrorCode.AFTERSALE_AMOUNT_EXCEED.getCode()
    );

    private MqErrorPolicy() {
    }

    /** true=不可恢复（ACK 丢弃并告警）；false=可恢复（broker 重试）。 */
    public static boolean isTerminal(Throwable t) {
        if (t instanceof BizException biz) {
            return TERMINAL_CODES.contains(biz.getCode());
        }
        // 非业务异常（NPE/超时/网络/Feign 5xx 等）默认可恢复，交给重试
        return false;
    }
}
