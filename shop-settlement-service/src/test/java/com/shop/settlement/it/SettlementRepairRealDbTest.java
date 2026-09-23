package com.shop.settlement.it;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.shop.api.order.client.OrderClient;
import com.shop.api.pay.enums.RefundTypes;
import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.mybatis.MybatisPlusConfig;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.framework.outbox.mapper.OutboxMapper;
import com.shop.settlement.account.entity.SettAccount;
import com.shop.settlement.account.mapper.AccountFlowMapper;
import com.shop.settlement.account.mapper.AccountMapper;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.entity.SettClearingReverse;
import com.shop.settlement.clearing.mapper.ClearingMapper;
import com.shop.settlement.clearing.mapper.ClearingReverseMapper;
import com.shop.settlement.clearing.service.ClearingReverseService;
import com.shop.settlement.clearing.service.ClearingService;
import com.shop.settlement.deposit.mapper.DepositLogMapper;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.engine.RefundCalculator;
import com.shop.settlement.engine.SettleCycle;
import com.shop.settlement.engine.SplitEngine;
import com.shop.settlement.merchant.mapper.MerchantMapper;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.mq.mapper.MqConsumeMapper;
import com.shop.settlement.mq.service.MqConsumeService;
import com.shop.settlement.statement.mapper.StatementMapper;
import com.shop.settlement.statement.service.SettleClearingExecutor;
import com.shop.settlement.statement.service.StatementSettleService;
import com.shop.settlement.support.LambdaTableSupport;
import com.shop.settlement.support.SettleNoGenerator;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * P1-4 / P1-10 / P1-12 / P1-1 真实 MySQL 集成测试（本地 docker shop-mysql:3306/shop_settlement）。
 *
 * <p>最小 Spring 容器（HikariCP + MyBatis-Plus + 真实 Mapper/服务 + 声明式事务），
 * 不启动 Nacos/RocketMQ/Redis：分布式锁用内联 mock（锁语义由 InnoDB 行锁保证），
 * outbox 只验证本地消息表落库/回滚，不依赖 relay 投递。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SettlementRepairRealDbTest.TestConfig.class)
class SettlementRepairRealDbTest {

    @Configuration
    @EnableTransactionManagement
    @Import(MybatisPlusConfig.class)
    @MapperScan(basePackages = {"com.shop.settlement", "com.shop.framework.outbox"},
            annotationClass = Mapper.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:mysql://localhost:3306/shop_settlement?useUnicode=true"
                    + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false"
                    + "&allowPublicKeyRetrieval=true");
            ds.setUsername("root");
            ds.setPassword("root");
            ds.setMaximumPoolSize(10);
            return ds;
        }

        @Bean
        org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor interceptor,
                com.baomidou.mybatisplus.core.handlers.MetaObjectHandler metaObjectHandler) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPlugins(interceptor);
            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);
            return factory.getObject();
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        IdGenerator idGenerator(ObjectProvider<org.redisson.api.RedissonClient> redissonProvider) {
            IdGenerator idGenerator = new IdGenerator(redissonProvider);
            idGenerator.init();
            return idGenerator;
        }

        @Bean
        SettleNoGenerator settleNoGenerator(IdGenerator idGenerator) {
            return new SettleNoGenerator(idGenerator);
        }

        @Bean
        OutboxPublisher outboxPublisher(OutboxMapper outboxMapper, IdGenerator idGenerator) {
            return new OutboxPublisher(outboxMapper, idGenerator);
        }

        @Bean
        MqConsumeService mqConsumeService(MqConsumeMapper mqConsumeMapper) {
            return new MqConsumeService(mqConsumeMapper);
        }

        @Bean
        AccountService accountService(AccountMapper accountMapper, AccountFlowMapper flowMapper,
                                     SettleNoGenerator noGenerator) {
            return new AccountService(accountMapper, flowMapper, noGenerator);
        }

        @Bean
        MerchantService merchantService(MerchantMapper merchantMapper, AccountService accountService) {
            return new MerchantService(merchantMapper, accountService);
        }

        @Bean
        DepositService depositService(MerchantMapper merchantMapper, MerchantService merchantService,
                                      DepositLogMapper depositLogMapper, SettleNoGenerator noGenerator,
                                      OutboxPublisher outboxPublisher, AccountService accountService) {
            // B10：缴费/清退真实链路依赖在本最小容器内以桩替代（PayClient/RemitRouter/AftersaleClient/
            // WithdrawMapper 不参与本 IT 覆盖的扣赔/冲正路径）；DataCipher 用测试默认密钥
            return new DepositService(merchantMapper, merchantService, depositLogMapper,
                    noGenerator, outboxPublisher,
                    mock(com.shop.api.pay.client.PayClient.class),
                    mock(com.shop.settlement.remit.RemitRouter.class),
                    mock(com.shop.api.aftersale.client.AftersaleClient.class),
                    mock(com.shop.settlement.withdraw.mapper.WithdrawMapper.class),
                    com.shop.settlement.support.DataCipher.forTest(
                            com.shop.settlement.support.DataCipher.DEV_DEFAULT_KEY, ""),
                    accountService, null);
        }

        @Bean
        ClearingService clearingService(ClearingMapper clearingMapper, MerchantService merchantService,
                                        MqConsumeService mqConsumeService, SettleNoGenerator noGenerator,
                                        OutboxPublisher outboxPublisher) {
            return new ClearingService(clearingMapper, merchantService, mqConsumeService,
                    noGenerator, new SplitEngine(), new SettleCycle(), outboxPublisher,
                    mock(OrderClient.class));
        }

        @Bean
        ClearingReverseService clearingReverseService(ClearingMapper clearingMapper,
                                                       ClearingReverseMapper reverseMapper,
                                                       MqConsumeService mqConsumeService,
                                                       AccountService accountService,
                                                       DepositService depositService,
                                                       SettleNoGenerator noGenerator,
                                                       OutboxPublisher outboxPublisher) {
            DistributedLockTemplate lock = mock(DistributedLockTemplate.class);
            doAnswer(inv -> {
                ((Runnable) inv.getArgument(1)).run();
                return null;
            }).when(lock).execute(anyString(), any(Runnable.class));
            return new ClearingReverseService(clearingMapper, reverseMapper, mqConsumeService,
                    new RefundCalculator(), accountService, depositService, lock,
                    noGenerator, outboxPublisher);
        }

        @Bean
        SettleClearingExecutor settleClearingExecutor(ClearingService clearingService,
                                                       StatementMapper statementMapper,
                                                       MerchantService merchantService,
                                                       AccountService accountService,
                                                       SettleNoGenerator noGenerator,
                                                       OutboxPublisher outboxPublisher) {
            return new SettleClearingExecutor(clearingService, statementMapper, merchantService,
                    accountService, noGenerator, outboxPublisher);
        }

        @Bean
        StatementSettleService statementSettleService(ClearingService clearingService,
                                                       SettleClearingExecutor executor,
                                                       StatementMapper statementMapper) {
            return new StatementSettleService(clearingService, executor, statementMapper);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private DataSource dataSource;
    @org.springframework.beans.factory.annotation.Autowired
    private AccountMapper accountMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private ClearingMapper clearingMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private ClearingReverseMapper reverseMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private ClearingReverseService reverseService;
    @org.springframework.beans.factory.annotation.Autowired
    private StatementSettleService statementSettleService;

    private JdbcTemplate jdbc;

    @BeforeAll
    static void initLambda() {
        LambdaTableSupport.init();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM t_sett_account_flow WHERE owner_id BETWEEN 9100 AND 9199 "
                + "OR biz_no LIKE 'RFIT%' OR biz_no LIKE 'CLIT%'");
        jdbc.update("DELETE FROM t_sett_deposit_log WHERE merchant_id BETWEEN 9100 AND 9199");
        jdbc.update("DELETE FROM t_sett_clearing_reverse WHERE merchant_id BETWEEN 9100 AND 9199 "
                + "OR refund_no LIKE 'RFIT%'");
        jdbc.update("DELETE FROM t_sett_clearing WHERE merchant_id BETWEEN 9100 AND 9199 "
                + "OR order_no LIKE 'OIT%'");
        jdbc.update("DELETE FROM t_sett_statement WHERE merchant_id BETWEEN 9100 AND 9199");
        jdbc.update("DELETE FROM t_sett_account WHERE owner_id BETWEEN 9100 AND 9199");
        jdbc.update("DELETE FROM t_sett_merchant WHERE id BETWEEN 9100 AND 9199");
        jdbc.update("DELETE FROM t_sett_mq_consume WHERE biz_no LIKE 'RFIT%' OR biz_no LIKE 'OIT%'");
        jdbc.update("DELETE FROM t_mq_outbox WHERE biz_key LIKE 'RFIT%' OR biz_key LIKE 'CLIT%'");
    }

    private void insertMerchant(long id, long deposit) {
        jdbc.update("INSERT INTO t_sett_merchant (id, merchant_name, merchant_level, commission_rate_bps, "
                + "deposit_balance_fen, deposit_required_fen, status) VALUES (?, 'IT商户', 2, 500, ?, 0, 1)",
                id, deposit);
    }

    private void insertAccount(long ownerId, int roleType, long available, long frozen, long pending) {
        jdbc.update("INSERT INTO t_sett_account (owner_id, role_type, available_fen, frozen_fen, pending_settle_fen) "
                + "VALUES (?, ?, ?, ?, ?)", ownerId, roleType, available, frozen, pending);
    }

    private SettClearing insertClearing(String clearingNo, String orderNo, long merchantId, int stage,
                                        long pay, long receivable, long commission, long subsidy,
                                        LocalDate dueDate) {
        SettClearing c = new SettClearing();
        c.setClearingNo(clearingNo);
        c.setOrderNo(orderNo);
        c.setPayNo("");
        c.setMerchantId(merchantId);
        c.setStage(stage);
        c.setProductAmountFen(pay);
        c.setFreightFen(0L);
        c.setPayAmountFen(pay);
        c.setMerchantReceivableFen(receivable);
        c.setPlatformCommissionFen(commission);
        c.setTechFeeFen(50L);
        c.setChannelFeeFen(60L);
        c.setMarketingSubsidyFen(subsidy);
        c.setCommissionRateBps(500);
        c.setReversedMerchantFen(0L);
        c.setReversedCommissionFen(0L);
        c.setReversedSubsidyFen(0L);
        c.setRefundedFen(0L);
        c.setDueDate(dueDate);
        clearingMapper.insert(c);
        return c;
    }

    private RefundSucceededEvent refundEvent(String refundNo, String orderNo, long amount, int type) {
        RefundSucceededEvent e = RefundSucceededEvent.builder()
                .refundNo(refundNo).orderNo(orderNo).amountFen(amount).refundType(type).build();
        // eventId 是消费幂等表 t_sett_mq_consume 的 NOT NULL 唯一键
        e.setEventId("EVT-" + refundNo);
        return e;
    }

    private SettAccount account(long merchantId) {
        return jdbc.queryForObject("SELECT * FROM t_sett_account WHERE owner_id=? AND role_type=2",
                (rs, n) -> {
                    SettAccount a = new SettAccount();
                    a.setAvailableFen(rs.getLong("available_fen"));
                    a.setFrozenFen(rs.getLong("frozen_fen"));
                    a.setPendingSettleFen(rs.getLong("pending_settle_fen"));
                    return a;
                }, merchantId);
    }

    private long depositOf(long merchantId) {
        return jdbc.queryForObject("SELECT deposit_balance_fen FROM t_sett_merchant WHERE id=?",
                Long.class, merchantId);
    }

    private SettClearingReverse reverseOf(String refundNo) {
        return reverseMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SettClearingReverse>()
                        .eq(SettClearingReverse::getRefundNo, refundNo));
    }

    @Test
    @DisplayName("P1-10_瀑布严格按待结算→可提现→保证金顺序扣减且三档金额落库正确")
    void waterfall_threeTiers_order() {
        insertMerchant(9101L, 100_000L);
        insertAccount(9101L, 2, 1_500L, 0L, 500L);
        insertClearing("CLIT1", "OIT1", 9101L, 30, 10_000L, 8_000L, 1_000L, 500L, null);

        // 部分退款 50%：商户承担 4000 = 待结算500 + 可提现1500 + 保证金2000
        reverseService.onRefundSucceeded(
                refundEvent("RFIT1", "OIT1", 5_000L, RefundTypes.PART.getCode()));

        SettAccount a = account(9101L);
        assertEquals(0L, a.getPendingSettleFen(), "待结算应扣尽");
        assertEquals(0L, a.getAvailableFen(), "可提现应扣尽");
        assertEquals(98_000L, depositOf(9101L), "保证金扣 2000");

        SettClearingReverse r = reverseOf("RFIT1");
        assertEquals(500L, r.getFromPendingFen());
        assertEquals(1_500L, r.getFromAvailableFen());
        assertEquals(2_000L, r.getFromDepositFen());
        assertEquals(0L, r.getShortfallFen());
        assertEquals(1, r.getStatus());
    }

    @Test
    @DisplayName("P1-10_三档扣尽仍不足_挂起status=2记shortfall同事务登记outbox且不抛异常")
    void allTiersExhausted_suspendAndOutbox() {
        insertMerchant(9102L, 1_000L);
        insertAccount(9102L, 2, 1_500L, 0L, 500L);
        insertClearing("CLIT2", "OIT2", 9102L, 30, 10_000L, 8_000L, 1_000L, 500L, null);

        // 商户承担 4000 = 500 + 1500 + 1000，缺口 1000；必须正常返回（消息可 ACK，不无限重试）
        reverseService.onRefundSucceeded(
                refundEvent("RFIT2", "OIT2", 5_000L, RefundTypes.PART.getCode()));

        assertEquals(0L, account(9102L).getPendingSettleFen());
        assertEquals(0L, account(9102L).getAvailableFen());
        assertEquals(0L, depositOf(9102L));
        SettClearingReverse r = reverseOf("RFIT2");
        assertEquals(1_000L, r.getShortfallFen());
        assertEquals(2, r.getStatus());
        Integer outboxRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_mq_outbox WHERE topic='shop_refund_shortfall' AND biz_key='RFIT2'",
                Integer.class);
        assertEquals(1, outboxRows, "缺口告警必须随冲正同事务落 outbox");
    }

    @Test
    @DisplayName("P1-12_并发两笔退款_待结算原子扣减合计不超过余额且剩余不遗漏追索到可提现")
    void concurrentRefunds_atomicPendingDeduct() throws Exception {
        insertMerchant(9103L, 100_000L);
        // 待结算共 100，可提现 200000：两笔各需扣商户 4000，待结算只能贡献 100
        insertAccount(9103L, 2, 200_000L, 0L, 100L);
        insertClearing("CLIT3A", "OIT3A", 9103L, 30, 10_000L, 8_000L, 1_000L, 500L, null);
        insertClearing("CLIT3B", "OIT3B", 9103L, 30, 10_000L, 8_000L, 1_000L, 500L, null);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (String refundNo : new String[]{"RFIT3A", "RFIT3B"}) {
            String orderNo = refundNo.replace("RFIT", "OIT");
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    reverseService.onRefundSucceeded(
                            refundEvent(refundNo, orderNo, 5_000L, RefundTypes.PART.getCode()));
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "并发冲正应在超时前完成");
        assertNull(err.get(), () -> "并发冲正不应有异常: " + err.get());

        SettClearingReverse ra = reverseOf("RFIT3A");
        SettClearingReverse rb = reverseOf("RFIT3B");
        assertNotNull(ra);
        assertNotNull(rb);
        // 待结算合计恰好扣 100（不多扣），其余 7900 全部追索到可提现（不遗漏）
        assertEquals(100L, ra.getFromPendingFen() + rb.getFromPendingFen(),
                "两笔并发退款待结算扣减合计必须等于余额100");
        assertEquals(7_900L, ra.getFromAvailableFen() + rb.getFromAvailableFen(),
                "待结算不足部分必须继续追索可提现");
        assertEquals(0L, ra.getFromDepositFen() + rb.getFromDepositFen(),
                "可提现充足时不得扣保证金");
        assertEquals(0L, ra.getShortfallFen() + rb.getShortfallFen());
        assertEquals(0L, account(9103L).getPendingSettleFen());
        assertEquals(192_100L, account(9103L).getAvailableFen());
        assertEquals(100_000L, depositOf(9103L));
    }

    @Test
    @DisplayName("P1-4_日终批单笔失败独立事务隔离_失败单停留stage20_其他单正常stage30")
    void dailyBatch_failureIsolation() {
        insertMerchant(9104L, 0L);
        LocalDate today = LocalDate.now();
        // 正常单：商户存在，stage=20 今日到期，净额 1000
        insertClearing("CLIT4OK", "OIT4OK", 9104L, 20, 10_000L, 1_000L, 100L, 0L, today);
        // 异常单：商户 9999 不存在，getOrCreateStatement 抛错（该笔独立事务回滚）
        insertClearing("CLIT4BAD", "OIT4BAD", 9999L, 20, 10_000L, 2_000L, 100L, 0L, today);

        // runDailySettle 扫描的是全库当日到期单：共享开发库中真实商户（E2E/混沌产生的订单，
        // T+1 到期）也会被结算并出现在返回值里。断言必须收敛到本用例的 IT 商户号段 9100-9199，
        // 否则用例在非 fresh 库上必然假失败（W7 实证：昨日 E2E 订单的清算单今日到期）。
        List<StatementSettleService.MerchantSettle> result = statementSettleService.runDailySettle(today)
                .stream()
                .filter(s -> s.getMerchantId() >= 9100 && s.getMerchantId() <= 9199)
                .toList();

        assertEquals(1, result.size(), "仅成功单产生汇总");
        assertEquals(1_000L, result.get(0).getAmountFen());

        Integer stageOk = jdbc.queryForObject(
                "SELECT stage FROM t_sett_clearing WHERE clearing_no='CLIT4OK'", Integer.class);
        Integer stageBad = jdbc.queryForObject(
                "SELECT stage FROM t_sett_clearing WHERE clearing_no='CLIT4BAD'", Integer.class);
        assertEquals(30, stageOk, "正常单转已结算");
        assertEquals(20, stageBad, "失败单回滚保持20，下轮自动重跑");
        assertEquals(1_000L, account(9104L).getAvailableFen(), "正常单已转可提现");
        Integer stmtCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_sett_statement WHERE merchant_id=9104", Integer.class);
        assertEquals(1, stmtCount);
        Integer settleEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_mq_outbox WHERE topic='shop_clearing_settle' AND biz_key='CLIT4OK'",
                Integer.class);
        assertEquals(1, settleEvents, "成功单结算事件与余额变更同事务登记 outbox");
    }

    @Test
    @DisplayName("P1-1_冲正后续阶段更新失败_余额扣减与outbox登记整体回滚_无幽灵事件无半截账")
    void outboxAndBalance_rollbackTogether() {
        insertMerchant(9105L, 0L);
        insertAccount(9105L, 2, 0L, 0L, 0L);
        // 清算单已处于 stage=40：全额冲正的阶段 CAS in(10,20,30) 影响 0 行，抛 CONFLICT
        insertClearing("CLIT5", "OIT5", 9105L, 40, 10_000L, 8_000L, 1_000L, 500L, null);

        assertThrows(Exception.class, () ->
                reverseService.onRefundSucceeded(
                        refundEvent("RFIT5", "OIT5", 10_000L, RefundTypes.FULL.getCode())));

        // 三档全空 → 缺口 8000 本应登记 outbox，但阶段 CAS 失败：整个事务回滚
        Integer outboxRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_mq_outbox WHERE biz_key='RFIT5'", Integer.class);
        assertEquals(0, outboxRows, "outbox 事件必须随业务事务回滚（无幽灵事件）");
        Integer reverseRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_sett_clearing_reverse WHERE refund_no='RFIT5'", Integer.class);
        assertEquals(0, reverseRows, "冲正明细回滚");
        Integer flowRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_sett_account_flow WHERE biz_no='RFIT5'", Integer.class);
        assertEquals(0, flowRows, "账户流水回滚（无半截账）");
        Integer consumeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_sett_mq_consume WHERE biz_no='RFIT5'", Integer.class);
        assertEquals(0, consumeRows, "消费登记随事务回滚，Broker 重试可重新处理");
    }
}
