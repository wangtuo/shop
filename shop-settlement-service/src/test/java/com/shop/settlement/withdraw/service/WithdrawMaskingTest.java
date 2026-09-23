package com.shop.settlement.withdraw.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.result.PageResult;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.engine.WithdrawCalculator;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.remit.RemitRouter;
import com.shop.settlement.support.DataCipher;
import com.shop.settlement.support.LambdaTableSupport;
import com.shop.settlement.support.SettleNoGenerator;
import com.shop.settlement.withdraw.dto.AutoWithdrawConfigRequest;
import com.shop.settlement.withdraw.entity.SettWithdraw;
import com.shop.settlement.withdraw.entity.SettWithdrawAutoConfig;
import com.shop.settlement.withdraw.mapper.WithdrawAutoConfigMapper;
import com.shop.settlement.withdraw.mapper.WithdrawDailyCountMapper;
import com.shop.settlement.withdraw.mapper.WithdrawMapper;
import com.shop.settlement.withdraw.vo.AutoWithdrawConfigVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M-1：商户侧查询只回脱敏账号；配置写库为密文、回显为脱敏。 */
@ExtendWith(MockitoExtension.class)
class WithdrawMaskingTest {

    @Mock private WithdrawMapper withdrawMapper;
    @Mock private WithdrawDailyCountMapper countMapper;
    @Mock private WithdrawAutoConfigMapper autoConfigMapper;
    @Mock private MerchantService merchantService;
    @Mock private DepositService depositService;
    @Mock private AccountService accountService;
    @Mock private DistributedLockTemplate lockTemplate;
    @Mock private SettleNoGenerator noGenerator;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private RemitRouter remitRouter;

    private DataCipher cipher;
    private WithdrawService service;

    @BeforeAll
    static void initLambdaCache() {
        LambdaTableSupport.init();
    }

    @BeforeEach
    void setUp() {
        cipher = DataCipher.forTest(DataCipher.DEV_DEFAULT_KEY, "");
        service = new WithdrawService(withdrawMapper, countMapper, autoConfigMapper, merchantService,
                depositService, accountService, new WithdrawCalculator(), lockTemplate,
                noGenerator, outboxPublisher, cipher, remitRouter);
    }

    @Test
    void 分页查询返回脱敏账号_密文不泄露() {
        SettWithdraw w = new SettWithdraw();
        w.setId(1L);
        w.setWithdrawNo("WD1");
        w.setMerchantId(7L);
        w.setAmountFen(100_000L);
        w.setChannelAccount(cipher.encrypt("6222020200001234"));
        w.setAccountName(cipher.encrypt("张三"));

        Page<SettWithdraw> page = new Page<>(1, 20);
        page.setRecords(List.of(w));
        page.setTotal(1);
        when(withdrawMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<com.shop.settlement.withdraw.vo.WithdrawVO> result =
                service.pageMerchant(7L, 1, 20);
        assertEquals(1, result.getTotal());
        com.shop.settlement.withdraw.vo.WithdrawVO vo = result.getList().get(0);
        assertEquals("**** **** **** 1234", vo.getChannelAccount());
        assertEquals("张*", vo.getAccountName());
        assertFalse(vo.getChannelAccount().contains("6222"));
    }

    @Test
    void 保存自动配置_落库密文回显脱敏() {
        AutoWithdrawConfigRequest req = new AutoWithdrawConfigRequest();
        req.setEnabled(1);
        req.setFrequency(1);
        req.setChannel(1);
        req.setChannelAccount("alipay0001@example.com");
        req.setAccountName("李四");
        when(autoConfigMapper.selectOne(any())).thenReturn(null);
        when(merchantService.requireMerchant(7L)).thenReturn(new com.shop.settlement.merchant.entity.SettMerchant());

        AutoWithdrawConfigVO vo = service.saveConfig(7L, req);

        ArgumentCaptor<SettWithdrawAutoConfig> cap = ArgumentCaptor.forClass(SettWithdrawAutoConfig.class);
        verify(autoConfigMapper).insert(cap.capture());
        assertTrue(cipher.isEncrypted(cap.getValue().getChannelAccount()));
        assertEquals("alipay0001@example.com", cipher.decrypt(cap.getValue().getChannelAccount()));
        assertEquals("**** **** **** .com", vo.getChannelAccount());
        assertEquals("李*", vo.getAccountName());
    }

    @Test
    void 查询自动配置_回显脱敏() {
        SettWithdrawAutoConfig config = new SettWithdrawAutoConfig();
        config.setMerchantId(7L);
        config.setEnabled(1);
        config.setFrequency(1);
        config.setChannel(2);
        config.setChannelAccount(cipher.encrypt("zhangsan199001018899"));
        config.setAccountName(cipher.encrypt("张三"));
        when(autoConfigMapper.selectOne(any())).thenReturn(config);

        AutoWithdrawConfigVO vo = service.getConfigMasked(7L);
        assertEquals("**** **** **** 8899", vo.getChannelAccount());
        assertEquals("张*", vo.getAccountName());
    }
}
