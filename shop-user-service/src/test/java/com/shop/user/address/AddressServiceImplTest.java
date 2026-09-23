package com.shop.user.address;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.user.address.dto.AddressSaveRequest;
import com.shop.user.address.entity.UserAddress;
import com.shop.user.address.mapper.UserAddressMapper;
import com.shop.user.address.service.impl.AddressServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 地址 20 条上限、默认地址唯一（切换事务内更新）、归属校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddressServiceImplTest {

    @Mock
    private UserAddressMapper addressMapper;
    @Mock
    private IdGenerator idGenerator;
    @InjectMocks
    private AddressServiceImpl addressService;

    private AddressSaveRequest req(boolean asDefault) {
        AddressSaveRequest r = new AddressSaveRequest();
        r.setReceiver("张三");
        r.setPhone("13800001111");
        r.setProvince("浙江省");
        r.setCity("杭州市");
        r.setDistrict("西湖区");
        r.setDetailAddress("文三路 1 号");
        r.setTag("家");
        r.setIsDefault(asDefault ? 1 : 0);
        return r;
    }

    @Test
    void create_首条地址_强制为默认并清除旧默认() {
        when(addressMapper.selectCount(any())).thenReturn(0L);
        addressService.create(1L, req(false));

        verify(addressMapper).clearDefault(1L);
        ArgumentCaptor<UserAddress> captor = ArgumentCaptor.forClass(UserAddress.class);
        verify(addressMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getIsDefault());
    }

    @Test
    void create_显式默认_清除旧默认() {
        when(addressMapper.selectCount(any())).thenReturn(3L);
        addressService.create(1L, req(true));
        verify(addressMapper).clearDefault(1L);
    }

    @Test
    void create_非默认且非首条_不动默认() {
        when(addressMapper.selectCount(any())).thenReturn(3L);
        addressService.create(1L, req(false));
        verify(addressMapper, never()).clearDefault(anyLong());
    }

    @Test
    void create_已有20条_拒绝() {
        when(addressMapper.selectCount(any())).thenReturn(20L);
        BizException ex = assertThrows(BizException.class, () -> addressService.create(1L, req(false)));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(addressMapper, never()).insert(any());
    }

    @Test
    void update_归属自己且设为默认_同事务清旧默认() {
        UserAddress old = new UserAddress();
        old.setId(9L);
        old.setUserId(1L);
        old.setIsDefault(0);
        when(addressMapper.selectById(9L)).thenReturn(old);

        addressService.update(1L, 9L, req(true));

        verify(addressMapper).clearDefault(1L);
        verify(addressMapper).updateById(any(UserAddress.class));
    }

    @Test
    void update_非归属用户_抛Forbidden() {
        UserAddress old = new UserAddress();
        old.setId(9L);
        old.setUserId(2L);
        when(addressMapper.selectById(9L)).thenReturn(old);

        BizException ex = assertThrows(BizException.class, () -> addressService.update(1L, 9L, req(true)));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(addressMapper, never()).clearDefault(anyLong());
    }

    @Test
    void delete_归属自己_删除成功() {
        UserAddress old = new UserAddress();
        old.setId(9L);
        old.setUserId(1L);
        when(addressMapper.selectById(9L)).thenReturn(old);
        addressService.delete(1L, 9L);
        verify(addressMapper).deleteById(9L);
    }

    @Test
    void getByIdInternal_不存在_抛NotFound() {
        when(addressMapper.selectById(404L)).thenReturn(null);
        assertThrows(BizException.class, () -> addressService.getByIdInternal(404L));
    }
}
