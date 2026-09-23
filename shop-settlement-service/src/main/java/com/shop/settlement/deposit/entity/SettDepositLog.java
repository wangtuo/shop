package com.shop.settlement.deposit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 保证金流水（t_sett_deposit_log）。
 *
 * <p>status 状态机（V5 D2）：</p>
 * <ul>
 *   <li>log_type=10 缴费：10 待支付（已建单未到账，不动余额）→ 20 成功（ORDER_PAID 到账 CAS）→ 30 失败/关单；</li>
 *   <li>log_type=40 清退退还：10 打款中（已建退还单/渠道已受理，余额保留）→ 20 成功（查询确认 CAS 后才置零）
 *       → 30 失败（保留余额，DEPOSIT_ALERT 人工处理）；</li>
 *   <li>log_type=20 扣赔 / 30 罚款：同步记账成功，恒为 20。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_deposit_log")
public class SettDepositLog extends BaseEntity {

    /** 处理中：缴费待支付 / 退还打款中 */
    public static final int STATUS_PROCESSING = 10;
    /** 成功 */
    public static final int STATUS_SUCCESS = 20;
    /** 失败（缴费关单 / 退还打款失败保留余额） */
    public static final int STATUS_FAIL = 30;

    private String logNo;
    private Long merchantId;
    /** 10 缴费 20 退款扣赔 30 罚款 40 清退退还 */
    private Integer logType;
    /** 单据状态，见类注释状态机 */
    private Integer status;
    /** 缴费支付单号（log_type=10），支付域 order_no = logNo 幂等 */
    private String payNo;
    /** 退还打款渠道流水号（log_type=40）；非空表示渠道已受理、待查询确认 */
    private String channelRemitNo;
    /** 退还打款最近查询时间（查询补偿 touch） */
    private LocalDateTime lastQueryTime;
    /** R4-25 清退人工挂起预警闸门：0未告警 1已告警（每笔 log40 仅一次，CAS 置位） */
    private Integer hangAlerted;
    /** 发生金额（分；缴纳为正，扣减/退还为负） */
    private Long amountFen;
    private Long balanceAfterFen;
    /** 业务幂等键：缴费=clientToken（可空）、罚款=clientToken、退还=merchantId+期号 */
    private String bizNo;
    private String remark;
}
