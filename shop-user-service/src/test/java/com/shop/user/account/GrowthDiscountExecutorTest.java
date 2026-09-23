package com.shop.user.account;

import com.shop.api.user.enums.MemberLevels;
import com.shop.framework.id.IdGenerator;
import com.shop.user.account.entity.UserGrowthDiscount;
import com.shop.user.account.mapper.UserGrowthDiscountMapper;
import com.shop.user.account.service.impl.GrowthDiscountExecutor;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 年末成长值 80% 折算保底等级：L1/L2/L3/L4 临界用户不允许降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrowthDiscountExecutorTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserGrowthDiscountMapper discountMapper;
    @Mock
    private IdGenerator idGenerator;
    @InjectMocks
    private GrowthDiscountExecutor executor;

    private User user(long id, long growth, int level) {
        User u = new User();
        u.setId(id);
        u.setGrowth(growth);
        u.setLevel(level);
        return u;
    }

    @Test
    void discount_L0用户50成长_折算40() {
        when(discountMapper.selectCount(any())).thenReturn(0L);
        boolean changed = executor.discount(user(1L, 50L, MemberLevels.L0), 2026);
        assertTrue(changed);
        verify(userMapper).updateGrowth(1L, 40L, MemberLevels.L0);
    }

    @Test
    void discount_L1用户150成长_折算120不降级() {
        when(discountMapper.selectCount(any())).thenReturn(0L);
        boolean changed = executor.discount(user(2L, 150L, MemberLevels.L1), 2026);
        assertTrue(changed);
        verify(userMapper).updateGrowth(2L, 120L, MemberLevels.L1);
    }

    @Test
    void discount_L2用户恰好1000_折算800保底1000() {
        when(discountMapper.selectCount(any())).thenReturn(0L);
        boolean changed = executor.discount(user(3L, 1000L, MemberLevels.L2), 2026);
        assertTrue(changed);
        verify(userMapper).updateGrowth(3L, 1000L, MemberLevels.L2);
    }

    @Test
    void discount_L3用户5000_保底5000() {
        when(discountMapper.selectCount(any())).thenReturn(0L);
        assertTrue(executor.discount(user(4L, 5000L, MemberLevels.L3), 2026));
        verify(userMapper).updateGrowth(4L, 5000L, MemberLevels.L3);
    }

    @Test
    void discount_L4用户20000_保底20000() {
        when(discountMapper.selectCount(any())).thenReturn(0L);
        assertTrue(executor.discount(user(5L, 20000L, MemberLevels.L4), 2026));
        verify(userMapper).updateGrowth(5L, 20000L, MemberLevels.L4);
    }

    @Test
    void discount_L3高成长19999_折算15999仍L3() {
        when(discountMapper.selectCount(any())).thenReturn(0L);
        assertTrue(executor.discount(user(6L, 19999L, MemberLevels.L3), 2026));
        verify(userMapper).updateGrowth(6L, 15999L, MemberLevels.L3);
    }

    @Test
    void discount_本年已折算_幂等跳过() {
        when(discountMapper.selectCount(any())).thenReturn(1L);
        assertFalse(executor.discount(user(1L, 1000L, 2), 2026));
        verify(discountMapper, never()).insert(any(UserGrowthDiscount.class));
        verify(userMapper, never()).updateGrowth(anyLong(), anyLong(), anyInt());
    }

    @Test
    void discount_唯一键并发冲突_跳过() {
        when(discountMapper.selectCount(any())).thenReturn(0L);
        when(discountMapper.insert(any(UserGrowthDiscount.class))).thenThrow(new DuplicateKeyException("uk"));
        assertFalse(executor.discount(user(1L, 1000L, 2), 2026));
        verify(userMapper, never()).updateGrowth(anyLong(), anyLong(), anyInt());
    }
}
