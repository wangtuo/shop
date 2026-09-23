package com.shop.settlement.remit;

import com.shop.common.exception.BizException;
import com.shop.settlement.enums.WithdrawChannels;
import com.shop.settlement.remit.adapter.real.AlipayRemitClient;
import com.shop.settlement.remit.adapter.real.BankRemitClient;
import com.shop.settlement.remit.adapter.real.RemitSecretProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打款 SPI 单测：mock 默认行为与渠道幂等、单笔终态注入、路由选择、
 * mock/real 开关装载矩阵、real 零伪成功与 prod 密钥 fail-fast。
 */
class RemitChannelWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of());

    private RemitRequest req(String bizNo, int channel) {
        return RemitRequest.builder()
                .bizNo(bizNo).merchantId(7L).channel(channel)
                .channelAccount("6222").accountName("张三").bankName("CMB")
                .amountFen(10_000L).remark("t").build();
    }

    @Test
    @DisplayName("mock_受理只生成渠道号不判成功_查询才成功_bizNo幂等同号")
    void mock_acceptAndQuery_idempotent() {
        MockRemitChannelClient client = new MockRemitChannelClient();
        RemitResult a1 = client.remit(req("WD1", WithdrawChannels.BANK_CARD));
        RemitResult a2 = client.remit(req("WD1", WithdrawChannels.BANK_CARD));
        assertTrue(a1.isAccepted());
        assertEquals(a1.getChannelRemitNo(), a2.getChannelRemitNo());

        RemitQueryResult q = client.query(RemitQueryRequest.builder()
                .bizNo("WD1").channel(1).channelRemitNo(a1.getChannelRemitNo()).build());
        assertEquals(RemitStatuses.SUCCESS, q.getStatus());

        // 未知单号查询不伪成功
        assertEquals(RemitStatuses.FAIL,
                client.query(RemitQueryRequest.builder().bizNo("WDX").channel(1).build()).getStatus());
    }

    @Test
    @DisplayName("mock_单笔处理中/失败注入与全局pending/failing开关")
    void mock_overrides() {
        MockRemitChannelClient client = new MockRemitChannelClient();
        client.remit(req("WD2", 1));
        client.forceProcessing("WD2");
        assertEquals(RemitStatuses.PROCESSING, client.query(query("WD2")).getStatus());
        client.forceFail("WD2");
        assertEquals(RemitStatuses.FAIL, client.query(query("WD2")).getStatus());

        MockRemitChannelClient alwaysPending = new MockRemitChannelClient();
        ReflectionTestUtils.setField(alwaysPending, "pendingAlways", true);
        alwaysPending.remit(req("WD3", 1));
        assertEquals(RemitStatuses.PROCESSING, alwaysPending.query(query("WD3")).getStatus());

        MockRemitChannelClient failing = new MockRemitChannelClient();
        ReflectionTestUtils.setField(failing, "failingAlways", true);
        assertFalse(failing.remit(req("WD4", 1)).isAccepted());
    }

    @Test
    @DisplayName("router_findFirst按渠道选择_无实现抛DEPENDENCY_FAIL")
    void router_selectsOrThrows() {
        MockRemitChannelClient mock = new MockRemitChannelClient();
        RemitRouter router = new RemitRouter(List.of(mock));
        assertSame(mock, router.route(WithdrawChannels.BANK_CARD));
        assertSame(mock, router.route(WithdrawChannels.ALIPAY));
        assertThrows(BizException.class, () -> router.route(99));
    }

    @Test
    @DisplayName("开关矩阵_缺省装载mock；mock-enabled=false装载real三件套不装载mock")
    void conditionalWiring_matrix() {
        // 缺省（matchIfMissing=true）
        runner.withUserConfiguration(MockRemitChannelClient.class)
                .run(ctx -> assertEquals(1, ctx.getBeansOfType(MockRemitChannelClient.class).size()));
        // 显式 false：mock 不装载
        runner.withPropertyValues("shop.settle.remit.mock-enabled=false")
                .withUserConfiguration(MockRemitChannelClient.class)
                .run(ctx -> assertTrue(ctx.getBeansOfType(MockRemitChannelClient.class).isEmpty()));
        // 显式 false：real 装载（非 prod 不做启动 fail-fast），路由按渠道分发
        runner.withPropertyValues("shop.settle.remit.mock-enabled=false")
                .withUserConfiguration(BankRemitClient.class, AlipayRemitClient.class,
                        RemitSecretProvider.class, RemitRouter.class)
                .run(ctx -> {
                    assertTrue(ctx.getBeansOfType(MockRemitChannelClient.class).isEmpty());
                    assertEquals(1, ctx.getBeansOfType(BankRemitClient.class).size());
                    assertEquals(1, ctx.getBeansOfType(AlipayRemitClient.class).size());
                    RemitRouter router = ctx.getBean(RemitRouter.class);
                    assertTrue(router.route(WithdrawChannels.BANK_CARD) instanceof BankRemitClient);
                    assertTrue(router.route(WithdrawChannels.ALIPAY) instanceof AlipayRemitClient);
                });
    }

    @Test
    @DisplayName("real骨架未联调_任何调用抛异常零伪成功")
    void realClients_neverFakeSuccess() {
        RemitSecretProvider provider = new RemitSecretProvider(
                new org.springframework.mock.env.MockEnvironment());
        ReflectionTestUtils.setField(provider, "requiredChannels", "BANK");
        BankRemitClient bank = new BankRemitClient(provider);
        AlipayRemitClient alipay = new AlipayRemitClient(provider);
        assertTrue(bank.supports(WithdrawChannels.BANK_CARD));
        assertTrue(alipay.supports(WithdrawChannels.ALIPAY));
        // 密钥未注入：requireSecret 先抛（生产由 fail-fast 拦截），任何路径都不返回伪成功
        assertThrows(IllegalStateException.class,
                () -> bank.remit(req("WDB", 1)));
        assertThrows(IllegalStateException.class,
                () -> alipay.query(query("WDA")));
    }

    @Test
    @DisplayName("prod密钥缺失_fail-fast拒绝启动(SHOP_SETTLE_REMIT_BANK_SECRET)")
    void secretProvider_prodFailFast() {
        assertEquals("SHOP_SETTLE_REMIT_BANK_SECRET", RemitSecretProvider.envName("BANK"));
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("prod");
        RemitSecretProvider provider = new RemitSecretProvider(env);
        ReflectionTestUtils.setField(provider, "requiredChannels", "BANK");
        // 本机测试环境不允许注入真实密钥；若恰好存在则跳过启动断言
        if (System.getenv(RemitSecretProvider.envName("BANK")) == null) {
            assertThrows(IllegalStateException.class, provider::failFastInProd);
        }
    }

    private RemitQueryRequest query(String bizNo) {
        return RemitQueryRequest.builder().bizNo(bizNo).channel(1).build();
    }
}
