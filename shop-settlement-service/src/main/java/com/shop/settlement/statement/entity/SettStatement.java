package com.shop.settlement.statement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商户结算单（t_sett_statement），按商户 + 周期日归集。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_statement")
public class SettStatement extends BaseEntity {

    private String statementNo;
    private Long merchantId;
    private Integer merchantLevel;
    /** 结算周期日（实际结算日，按日归集） */
    private LocalDate periodDate;
    private Long totalFen;
    private Long settledFen;
    private Long freezingFen;
    private Integer clearingCount;
    /** 20 待结算 30 已结算可提现 */
    private Integer stage;
    private LocalDateTime settleTime;

    @Version
    private Integer version;
}
