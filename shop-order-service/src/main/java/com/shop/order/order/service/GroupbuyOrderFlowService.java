package com.shop.order.order.service;

import com.shop.api.order.dto.GroupFailedCommand;
import com.shop.api.order.dto.GroupPayRenewCommand;
import com.shop.api.order.dto.GroupSucceedCommand;

/**
 * 拼团订单流转（B1）：GROUPBUY_EVENT 批量事件与营销域 inner Feign 逐单命令双通路收敛到同一套
 * CAS 逻辑，重复到达天然幂等。
 *
 * <ul>
 *     <li>成团（SUCCESS / renew / succeed）：全团 status=10 待付款单 expire_time CAS 续期
 *     （只宽不窄）并重投 ORDER_PAY_TIMEOUT 延时消息；status=20 不动；其余跳过；</li>
 *     <li>失败（FAIL / failed）：status=10 复用超时取消同路径关单 status=50、cancel_type=4，
 *     ORDER_CANCELLED outbox 释放资源；status=20 已支付单每单调一次 PayClient.refund
 *     （refundNo={@code GB:orderNo} 资金侧幂等），调用失败抛错等 MQ/Feign 重试，
 *     禁止伪成功、禁止自建退款表/状态机（MASTER 裁决⑩）。</li>
 * </ul>
 */
public interface GroupbuyOrderFlowService {

    /** MQ 批量通路：成团（type=3）。 */
    void onGroupSuccess(String groupNo);

    /** MQ 批量通路：拼团失败（type=4）。 */
    void onGroupFail(String groupNo);

    /** Feign 逐单通路：成团后续期支付截止时间（plusSeconds 为空取 30min）。 */
    void renewGroupPayDeadline(GroupPayRenewCommand command);

    /** Feign 逐单通路：成团标记（未付单续期、已付单不动）。 */
    void markSucceeded(GroupSucceedCommand command);

    /** Feign 逐单通路：失败关单/退款。 */
    void markFailed(GroupFailedCommand command);
}
