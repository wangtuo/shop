package com.shop.settlement.clearing.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 退款穿仓缺口工单（t_sett_shortfall_workorder，P0-1）。
 *
 * <p>幂等键：{@link #eventId}（uk_event_id）；同一冲正不重复开单另由
 * {@link #reverseNo} 先查后插 + 扫表 cutoff 兜底（DDL 无 reverse_no 唯一键，见实现注释）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_shortfall_workorder")
public class ShortfallWorkOrder extends BaseEntity {

    /** 10 待处理（已落单未告警） */
    public static final int STATUS_PENDING = 10;
    /** 20 已告警（追缴中，等待商户后续入账/补缴） */
    public static final int STATUS_ALERTED = 20;
    /** 30 已追缴结清（保证金自动补扣补满） */
    public static final int STATUS_CLOSED = 30;
    /** 40 人工核销（运营后台处理，本期不新增端点） */
    public static final int STATUS_WRITTEN_OFF = 40;

    /** REFUND_SHORTFALL 事件ID（幂等键，扫表补单为 RESIDUAL-+reverseNo） */
    private String eventId;
    /** 冲正流水号 */
    private String reverseNo;
    /** 退款单号 */
    private String refundNo;
    /** 订单号 */
    private String orderNo;
    /** 商户ID */
    private Long merchantId;
    /** 挂起缺口金额（分） */
    private Long shortfallFen;
    /** 已自动补扣金额（分） */
    private Long clawedBackFen;
    /** 10待处理 20已告警 30已追缴结清 40人工核销 */
    private Integer status;
    /** 告警次数 */
    private Integer alertCount;
    /** 处理备注（告警通道故障留痕等；DDL 无 last_error 列，失败信息落此列） */
    private String remark;
}
