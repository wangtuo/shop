package com.shop.settlement.support;

import com.shop.framework.id.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 清算域业务单号生成（CONTRACTS.md §6）：
 * CL 清算单 / RV 冲正单 / ST 结算单 / WD 提现单 / F 账户流水 / DP 保证金流水。
 *
 * <p>结构：前缀 + yyMMdd（6 位日）+ 雪花序列尾部 10 位补零，数据库唯一索引兜底防重。
 */
@Component
@RequiredArgsConstructor
public class SettleNoGenerator {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final long SEQ_MOD = 10_000_000_000L;

    private final IdGenerator idGenerator;

    public String nextClearingNo() {
        return build("CL");
    }

    public String nextReverseNo() {
        return build("RV");
    }

    public String nextStatementNo() {
        return build("ST");
    }

    public String nextWithdrawNo() {
        return build("WD");
    }

    public String nextFlowNo() {
        return build("F");
    }

    public String nextDepositLogNo() {
        return build("DP");
    }

    private String build(String prefix) {
        String day = LocalDate.now().format(YYMMDD);
        String seq = String.format("%010d", Math.floorMod(idGenerator.nextId(), SEQ_MOD));
        return prefix + day + seq;
    }
}
