package com.shop.settlement.withdraw.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商户提现单（t_sett_withdraw），状态 10/20/30/40/50。
 *
 * <p>B10 打款三段式：审核通过 status=20（冻结早已在申请时完成）→ 批次事务外提交渠道代发，
 * 受理后保持 status=20 并写 channel_remit_no（「打款中」）→ RemitQueryJob 查询终态：
 * 成功 CAS 20→30 才解冻出款 + 手续费 23 + WITHDRAW_RESULT；失败 markFailed 20→40
 * 冻结退回（流水 22）+ WITHDRAW_RESULT(失败)。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_withdraw")
public class SettWithdraw extends BaseEntity {

    private String withdrawNo;
    private Long merchantId;
    private Long amountFen;
    private Long feeFen;
    /** 1 银行卡 2 支付宝 */
    private Integer channel;
    private String channelAccount;
    private String accountName;
    private String bankName;
    /** 渠道代发流水号：非空表示渠道已受理、待查询确认（V5 新增，uk） */
    private String channelRemitNo;
    /** 打款失败原因（渠道返回/人工标记，V5 新增） */
    private String remitFailReason;
    /** 最近打款查询时间（查询补偿 touch，V5 新增） */
    private LocalDateTime lastQueryTime;
    /** 打款主动查询次数（V5 新增） */
    private Integer queryCount;
    private Integer status;
    private LocalDate applyDate;
    /** 本笔免手续费：0 否 1 是 */
    private Integer freeOfCharge;
    /** 自动提现生成：0 否 1 是 */
    private Integer autoWithdraw;
    private String failReason;
    private LocalDateTime auditTime;
    private LocalDateTime remitTime;

    @Version
    private Integer version;
}
