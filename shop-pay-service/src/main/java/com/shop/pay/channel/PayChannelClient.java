package com.shop.pay.channel;

import java.time.LocalDate;
import java.util.List;

/**
 * 支付渠道适配器：屏蔽第三方差异，当前实现 {@link MockPayChannelClient}。
 * 余额支付不走该适配器，由支付域直接经 UserClient 扣减/退回余额账户。
 */
public interface PayChannelClient {

    /** 是否承接该渠道编码 */
    boolean supports(String channelCode);

    /** 渠道下单，返回支付参数 */
    ChannelPayResult createOrder(ChannelPayRequest request);

    /** 主动查询支付状态（补偿双保险） */
    ChannelQueryResult query(String channelCode, String channelOrderNo);

    /** 原路退款 */
    ChannelRefundResult refund(ChannelRefundRequest request);

    /**
     * 主动查询退款状态（B8）：退款受理中（10）的补偿通道，结果与异步回调进入同一收敛漏斗。
     */
    ChannelRefundQueryResult queryRefund(ChannelRefundQueryRequest request);

    /**
     * T+1 分页拉取渠道成功账单。
     *
     * @param billDate 账单日期 T
     * @return 归一化账单记录；mock 默认返回空（可由 classpath 对账文件或测试构造注入）
     */
    List<ChannelBillRecord> downloadBill(String channelCode, LocalDate billDate);
}
