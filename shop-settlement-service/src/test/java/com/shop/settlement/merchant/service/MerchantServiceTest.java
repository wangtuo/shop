package com.shop.settlement.merchant.service;

import com.shop.api.settlement.enums.AccountRole;
import com.shop.api.settlement.enums.MerchantLevels;
import com.shop.common.exception.BizException;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.enums.MerchantStatuses;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.mapper.MerchantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商户档案服务单测：入驻默认值/校验/自动开户、等级调整、经营状态校验。
 */
@ExtendWith(MockitoExtension.class)
class MerchantServiceTest {

    @Mock private MerchantMapper merchantMapper;
    @Mock private AccountService accountService;

    private MerchantService service;

    @BeforeEach
    void setUp() {
        service = new MerchantService(merchantMapper, accountService);
    }

    @Test
    @DisplayName("onboard_合法入驻_默认C级零余额正常状态并开立商户账户")
    void onboard_ok_defaults() {
        when(merchantMapper.selectById(777L)).thenReturn(null);

        SettMerchant m = service.onboard(777L, "测试店", 9L, "手机", 500, 200_000L);

        assertEquals(MerchantLevels.C, m.getMerchantLevel());
        assertEquals(0L, m.getDepositBalanceFen());
        assertEquals(200_000L, m.getDepositRequiredFen());
        assertEquals(MerchantStatuses.NORMAL, m.getStatus());
        assertEquals(0, m.getDepositAlerted());
        verify(merchantMapper).insert(m);
        verify(accountService).getOrCreate(777L, AccountRole.MERCHANT);
    }

    @Test
    @DisplayName("onboard_参数非法_抛参数异常不开户")
    void onboard_invalid_throw() {
        assertThrows(BizException.class,
                () -> service.onboard(0L, "x", 1L, "c", 100, 200_000L));
        assertThrows(BizException.class,
                () -> service.onboard(1L, "x", 1L, "c", 10_001, 200_000L));
        assertThrows(BizException.class,
                () -> service.onboard(1L, "x", 1L, "c", 100, 99_999L));   // < 1000元
        assertThrows(BizException.class,
                () -> service.onboard(1L, "x", 1L, "c", 100, 5_000_001L)); // > 50000元
        verify(merchantMapper, never()).insert(any());
        verify(accountService, never()).getOrCreate(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("onboard_重复入驻_抛冲突异常")
    void onboard_duplicate_throw() {
        when(merchantMapper.selectById(777L)).thenReturn(new SettMerchant());
        assertThrows(BizException.class,
                () -> service.onboard(777L, "x", 1L, "c", 100, 200_000L));
    }

    @Test
    @DisplayName("updateLevel_合法等级落库_非法等级抛异常")
    void updateLevel_validation() {
        SettMerchant m = new SettMerchant();
        m.setId(777L);
        m.setMerchantLevel(MerchantLevels.C);
        when(merchantMapper.selectById(777L)).thenReturn(m);

        service.updateLevel(777L, MerchantLevels.S);
        assertEquals(MerchantLevels.S, m.getMerchantLevel());
        verify(merchantMapper).updateById(m);

        assertThrows(BizException.class, () -> service.updateLevel(777L, 9));
    }

    @Test
    @DisplayName("requireMerchant_不存在_抛NOT_FOUND")
    void requireMerchant_missing_throw() {
        when(merchantMapper.selectById(1L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.requireMerchant(1L));
    }

    @Test
    @DisplayName("requireActiveMerchant_禁用/清退_抛FORBIDDEN；清退观察期可操作")
    void requireActive_statusChecks() {
        SettMerchant disabled = new SettMerchant();
        disabled.setStatus(MerchantStatuses.DISABLED);
        when(merchantMapper.selectById(1L)).thenReturn(disabled);
        assertThrows(BizException.class, () -> service.requireActiveMerchant(1L));

        SettMerchant resigning = new SettMerchant();
        resigning.setStatus(MerchantStatuses.RESIGNING);
        when(merchantMapper.selectById(2L)).thenReturn(resigning);
        assertEquals(resigning, service.requireActiveMerchant(2L));
    }

    @Test
    @DisplayName("listByStatus_透传mapper结果")
    void listByStatus_ok() {
        when(merchantMapper.selectList(any())).thenReturn(List.of(new SettMerchant()));
        assertEquals(1, service.listByStatus(MerchantStatuses.RESIGNING).size());
    }
}
