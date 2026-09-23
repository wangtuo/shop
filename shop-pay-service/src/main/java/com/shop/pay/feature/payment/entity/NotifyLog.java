package com.shop.pay.feature.payment.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 渠道回调幂等日志（t_pay_notify_log）。
 * signStatus 0待验 1通过 2失败；handleStatus 0待处理 1成功 2验签失败 3失败。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pay_notify_log")
public class NotifyLog extends BaseEntity {

    private String channelCode;
    /** 渠道通知 ID（与 channelCode 联合唯一，幂等键） */
    private String notifyId;
    private String payNo;
    /** B8：退款回调（notify_type=2）时填写退款单号，支付回调为 null */
    private String refundNo;
    private String channelTxnNo;
    /** 1 支付回调 2 退款回调 */
    private Integer notifyType;
    private Integer signStatus;
    private Integer handleStatus;
    private String notifyBody;
    private String failReason;
}
