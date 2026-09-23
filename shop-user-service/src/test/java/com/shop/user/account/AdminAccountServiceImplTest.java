package com.shop.user.account;

import cn.hutool.crypto.digest.BCrypt;
import com.shop.api.user.enums.UserStatuses;
import com.shop.api.user.enums.UserTypes;
import com.shop.common.exception.BizException;
import com.shop.framework.id.IdGenerator;
import com.shop.user.account.dto.AdminCreateAccountRequest;
import com.shop.user.account.service.AccountService;
import com.shop.user.account.service.impl.AdminAccountServiceImpl;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台开通商户/运营账号：身份由服务端通道强制写入，调用方无法指定；
 * merchantId 绑定新商户账号自身 ID。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAccountServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private AccountService accountService;
    @Mock
    private IdGenerator idGenerator;
    @InjectMocks
    private AdminAccountServiceImpl service;

    private AdminCreateAccountRequest req(String username, String phone) {
        AdminCreateAccountRequest r = new AdminCreateAccountRequest();
        r.setUsername(username);
        r.setPhone(phone);
        r.setPassword("secret123");
        r.setNickname(username);
        return r;
    }

    @Test
    void createMerchantAccount_类型强制商户且merchantId绑定自身() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(idGenerator.nextId()).thenReturn(5001L);

        long id = service.createMerchantAccount(req("shop1", "13800001111"));
        assertEquals(5001L, id);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals(UserTypes.MERCHANT, saved.getUserType());
        assertEquals(5001L, saved.getMerchantId());
        assertEquals(UserStatuses.NORMAL, saved.getStatus());
        assertTrue(BCrypt.checkpw("secret123", saved.getPassword()));
        verify(accountService).initAccounts(5001L);
    }

    @Test
    void createPlatformAccount_类型强制平台且无商户绑定() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(idGenerator.nextId()).thenReturn(5002L);

        long id = service.createPlatformAccount(req("ops1", "13800002222"));
        assertEquals(5002L, id);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals(UserTypes.PLATFORM, captor.getValue().getUserType());
        assertNull(captor.getValue().getMerchantId());
    }

    @Test
    void createMerchantAccount_手机号冲突_拒绝() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BizException.class, () -> service.createMerchantAccount(req("shop2", "13800003333")));
        verify(userMapper, org.mockito.Mockito.never()).insert(any());
    }
}
