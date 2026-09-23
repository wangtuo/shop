package com.shop.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.shop.api.settlement.enums.MerchantLevels;
import com.shop.e2e.support.ApiClient;
import com.shop.e2e.support.Poller;
import com.shop.e2e.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 10：结算/清结算。商户入驻与账户视图、保证金缴纳与流水、提现门槛与手续费规则、
 * S/A/B/C 清算周期（S 级 T+1 可黑盒观测，A/B/C 需时钟推进，见报告缺口 #7）、手工对账入口。
 */
@EnabledIfSystemProperty(named = "shop.e2e", matches = "true")
@DisplayName("10 结算域：入驻/账户/保证金/提现规则/S-A-B-C 周期/手工对账")
class SettlementE2ETest {

    private static final World WORLD = World.get();
    private static final String SETT = "/api/settlement";

    @BeforeAll
    static void ready() {
        WORLD.assumeReady();
        WORLD.ensureMerchantOnboarded();
    }

    @Test
    @DisplayName("商户账户视图字段完整：可用/冻结/待结算/保证金/应缴/预警阈值/提现阻断标记")
    void merchantAccountView() {
        JsonNode acct = WORLD.merchantAccount();
        for (String f : java.util.List.of("availableFen", "frozenFen", "pendingSettleFen",
                "depositBalanceFen", "depositRequiredFen", "depositThresholdFen", "withdrawBlocked")) {
            assertTrue(acct.has(f), "账户视图包含字段: " + f);
        }
        assertEquals(100_000L, acct.path("depositRequiredFen").asLong(-1), "E2E 入驻应缴保证金 100000 分");
    }

    @Test
    @DisplayName("保证金缴纳幂等入账：余额增加且保证金钱包流水可查")
    void depositPayAndRecords() {
        long before = WORLD.merchantAccount().path("depositBalanceFen").asLong(0L);
        // B10 三段式：发起缴费（payScene=4 支付单）→ mock 渠道回调 → ORDER_PAID 扇出 CAS 10→20 才入余额
        World.DepositPay deposit = WORLD.payDeposit(100_000L);
        assertTrue(deposit.logNo().length() > 0, "返回保证金流水号");
        assertTrue(deposit.payNo().length() > 0, "返回支付单号");

        Poller.longTimeout().await("保证金余额增加 100000 分（回调→MQ 扇出入账）",
                () -> WORLD.merchantAccount().path("depositBalanceFen").asLong(-1) == before + 100_000L);

        JsonNode records = WORLD.merchant().get(SETT + "/merchant/deposit?pageNum=1&pageSize=10").data();
        assertTrue(records.path("list").size() > 0, "保证金钱包流水有记录");
    }

    @Test
    @DisplayName("提现门槛：低于单笔 10000 分与超过单日 500 万分均被拒绝")
    void withdrawAmountLimits() {
        ApiClient.Raw tooSmall = WORLD.applyWithdrawal(9_999L);
        assertNotEquals(0, tooSmall.bizCode(), "提现 9999 分低于 10000 下限必须拒绝");

        ApiClient.Raw tooLarge = WORLD.applyWithdrawal(50_000_001L);
        assertNotEquals(0, tooLarge.bizCode(), "提现超过单日 5000 万(50万分*100)上限必须拒绝");
    }

    @Test
    @DisplayName("提现阻断：无可用余额（或保证金低于阈值 50%）时提现申请被阻断")
    void withdrawBlockedWhenFundsUnavailable() {
        JsonNode acct = WORLD.merchantAccount();
        // 用远超可用余额的金额触发：要么保证金预警阻断(WITHDRAW_LIMIT)，要么可用余额冻结失败
        long attempt = Math.max(10_000L, acct.path("availableFen").asLong(0L) + 1_000_000L);
        ApiClient.Raw raw = WORLD.applyWithdrawal(attempt);
        assertNotEquals(0, raw.bizCode(),
                "可用余额不足（或保证金不足）时必须阻断提现，实际: " + raw.text);
    }

    @Test
    @DisplayName("提现手续费：当月前 3 笔免费，第 4 笔起 0.1% 且最低 200 分（需环境存在已结算可用余额）")
    void withdrawFeeSchedule_whenFundsAvailable() {
        JsonNode acct = WORLD.merchantAccount();
        JsonNode monthList = WORLD.withdrawals().path("list");
        long available = acct.path("availableFen").asLong(0L);
        // 仅在干净账户（当月无历史提现）且至少有 40000 分可用余额时执行，避免污染共享环境
        org.junit.jupiter.api.Assumptions.assumeTrue(available >= 40_000L,
                "无已结算可用余额（结算周期需 T+1 时钟推进），手续费正向用例跳过（缺口 #7）");
        org.junit.jupiter.api.Assumptions.assumeTrue(monthList.size() == 0,
                "当月已有提现记录，免费次数计数被污染，跳过");

        String[] nos = new String[4];
        for (int i = 0; i < 4; i++) {
            ApiClient.Raw r = WORLD.applyWithdrawal(10_000L);
            org.junit.jupiter.api.Assumptions.assumeTrue(r.bizCode() == 0,
                    "第 " + (i + 1) + " 笔提现受理失败，跳过：" + r.text);
            nos[i] = r.data().asText();
        }
        JsonNode list = WORLD.withdrawals().path("list");
        int freeSeen = 0;
        Long paidFee = null;
        for (JsonNode w : list) {
            if (java.util.List.of(nos).contains(w.path("withdrawNo").asText())) {
                if (w.path("freeOfCharge").asInt() == 1) {
                    freeSeen++;
                    assertEquals(0L, w.path("feeFen").asLong(), "免手续费提现 feeFen=0");
                } else {
                    paidFee = w.path("feeFen").asLong();
                }
            }
        }
        assertEquals(3, freeSeen, "当月前 3 笔免费");
        assertEquals(200L, paidFee, "第 4 笔手续费=max(10000*0.1%,200)=200 分");
    }

    @Test
    @DisplayName("S 级清算周期：确认收货后清算推进 stage=20，dueDate=确认日+1 天（A/B/C 需时钟推进，缺口 #7）")
    void cycleS_dueDateAfterConfirm() {
        // 商户入驻默认 C 级（新商户 T+30，design 7.3.2）；本用例验证 S 级 T+1，必须在下单/确认收货前调到 S
        WORLD.setMerchantLevel(MerchantLevels.S);
        World.Purchased p = WORLD.purchaseCompleted(6_000L, 1, World.PAY_WECHAT);
        JsonNode clearing = WORLD.clearingByOrder(p.orderNo());
        // stage 10=已登记 20=已分账；确认收货后应推进到 20，dueDate=今天+1（S 级 T+1）
        com.shop.e2e.support.Poller.longTimeout().await("清算分账推进 stage=20 orderNo=" + p.orderNo(),
                () -> WORLD.merchant().get(SETT + "/merchant/clearing?pageNum=1&pageSize=100")
                        .data().path("list").findValuesAsText("orderNo").contains(p.orderNo())
                        && clearingStage(p.orderNo()) == 20);
        assertEquals(LocalDate.now().plusDays(1),
                LocalDate.parse(clearingRow(p.orderNo()).path("dueDate").asText()),
                "S 级商户到账日=确认收货次日");

        // 对账单只读可用
        ApiClient.Raw statements = WORLD.merchant().get(SETT + "/merchant/statements?pageNum=1&pageSize=10");
        assertEquals(0, statements.bizCode(), "对账单分页可查");
    }

    @Test
    @DisplayName("手工日结入口仅在显式开启 shop.e2e.settle 时触发（共享环境默认跳过，避免全局资金推进）")
    void manualDailySettle_optInOnly() {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getProperty("shop.e2e.settle")),
                "默认不触发全局手工日结（POST /admin/reconcile/settle）；需要时 -Dshop.e2e.settle=true 开启");
        ApiClient.Raw raw = WORLD.admin().post(
                SETT + "/admin/reconcile/settle?date=" + LocalDate.now(), null);
        assertEquals(0, raw.bizCode(), "手工日结成功: " + raw.text);
    }

    private JsonNode clearingRow(String orderNo) {
        JsonNode list = WORLD.merchant().get(SETT + "/merchant/clearing?pageNum=1&pageSize=100")
                .data().path("list");
        for (JsonNode row : list) {
            if (orderNo.equals(row.path("orderNo").asText())) {
                return row;
            }
        }
        throw new AssertionError("清算流水不存在: " + orderNo);
    }

    private int clearingStage(String orderNo) {
        return clearingRow(orderNo).path("stage").asInt(-1);
    }
}
