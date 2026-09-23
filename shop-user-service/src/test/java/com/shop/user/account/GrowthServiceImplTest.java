package com.shop.user.account;

import com.shop.api.user.dto.GrowthCommand;
import com.shop.api.user.dto.UserLevelDTO;
import com.shop.api.user.enums.GrowthScene;
import com.shop.api.user.enums.MemberLevels;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.user.account.entity.UserGrowthFlow;
import com.shop.user.account.mapper.UserGrowthDiscountMapper;
import com.shop.user.account.mapper.UserGrowthFlowMapper;
import com.shop.user.account.service.impl.GrowthDiscountExecutor;
import com.shop.user.account.service.impl.GrowthServiceImpl;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成长值变更重算等级、年末折算保底等级、幂等用例。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrowthServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserGrowthFlowMapper growthFlowMapper;
    @Mock
    private UserGrowthDiscountMapper discountMapper;
    @Mock
    private GrowthDiscountExecutor discountExecutor;
    @Mock
    private IdGenerator idGenerator;
    @InjectMocks
    private GrowthServiceImpl growthService;

    private User user(long id, long growth, int level) {
        User u = new User();
        u.setId(id);
        u.setGrowth(growth);
        u.setLevel(level);
        u.setUsername("u" + id);
        return u;
    }

    @Test
    void addGrowth_从99增1_跨过L1下限_等级重算为1() {
        when(growthFlowMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectById(1L)).thenReturn(user(1L, 99L, 0));

        growthService.addGrowth(GrowthCommand.builder()
                .userId(1L).bizNo("O1").growth(1).scene(GrowthScene.CONSUME).build());

        verify(userMapper).updateGrowth(1L, 100L, MemberLevels.L1);
    }

    @Test
    void addGrowth_从4999增1_升到L2() {
        when(growthFlowMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectById(1L)).thenReturn(user(1L, 4999L, 1));

        growthService.addGrowth(GrowthCommand.builder()
                .userId(1L).bizNo("O2").growth(1).scene(GrowthScene.CONSUME).build());

        verify(userMapper).updateGrowth(1L, 5000L, MemberLevels.L3);
    }

    @Test
    void addGrowth_bizNo重复_幂等不重复记账() {
        when(growthFlowMapper.selectCount(any())).thenReturn(1L);

        growthService.addGrowth(GrowthCommand.builder()
                .userId(1L).bizNo("O1").growth(10).scene(GrowthScene.COMMENT).build());

        verify(userMapper, never()).updateGrowth(any(), anyLong(), anyInt());
    }

    @Test
    void addGrowth_成长值非正_抛参数错误() {
        assertThrows(BizException.class, () -> growthService.addGrowth(GrowthCommand.builder()
                .userId(1L).bizNo("O3").growth(0).build()));
    }

    @Test
    void addGrowth_用户不存在_抛NotFound() {
        when(growthFlowMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectById(404L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> growthService.addGrowth(GrowthCommand.builder()
                .userId(404L).bizNo("O4").growth(1).build()));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void getLevel_等级权益正确() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, 1500L, 2));
        UserLevelDTO dto = growthService.getLevel(1L);
        assertEquals(2, dto.getLevel());
        assertEquals("金卡会员", dto.getLevelName());
        assertEquals("0.95", dto.getDiscount().toPlainString());
        assertEquals("1.5", dto.getPointsRate().toPlainString());
        assertEquals(1500L, dto.getGrowth());
    }
}
