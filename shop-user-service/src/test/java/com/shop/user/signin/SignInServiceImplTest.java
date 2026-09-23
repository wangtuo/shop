package com.shop.user.signin;

import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.GrowthCommand;
import com.shop.common.exception.BizException;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.user.account.entity.UserSignIn;
import com.shop.user.account.mapper.UserSignInMapper;
import com.shop.user.account.service.AccountService;
import com.shop.user.account.service.GrowthService;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import com.shop.user.signin.dto.SignInResult;
import com.shop.user.signin.service.impl.SignInServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签到连签续期/中断重置、第 7 天 +50 成长值、当日重复与并发幂等用例。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignInServiceImplTest {

    private static final Long UID = 1001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    @Mock
    private DistributedLockTemplate lockTemplate;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserSignInMapper signInMapper;
    @Mock
    private AccountService accountService;
    @Mock
    private GrowthService growthService;
    @InjectMocks
    private SignInServiceImpl signInService;

    @BeforeEach
    void setUp() {
        // 锁模板直接执行，不依赖 Redisson
        when(lockTemplate.execute(anyString(), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    private User user(LocalDate lastSignDate, Integer continuousDays) {
        User u = new User();
        u.setId(UID);
        u.setLastSignDate(lastSignDate);
        u.setContinuousDays(continuousDays);
        u.setLevel(0);
        return u;
    }

    @Test
    void sign_首次签到_连签1天_5积分无成长值() {
        when(userMapper.selectById(UID)).thenReturn(user(null, 0));
        when(signInMapper.selectOne(any())).thenReturn(null);
        when(userMapper.applySignIn(UID, TODAY, 1)).thenReturn(1);

        SignInResult result = signInService.sign(UID, TODAY);

        assertEquals(1, result.getContinuousDays());
        assertEquals(5L, result.getPointsEarned());
        assertEquals(0, result.getGrowthEarned());

        ArgumentCaptor<GrantPointsCommand> captor = ArgumentCaptor.forClass(GrantPointsCommand.class);
        verify(accountService).grantPoints(captor.capture());
        assertEquals("SIGN:" + UID + ":2026-09-16", captor.getValue().getBizNo());
        assertEquals(5L, captor.getValue().getPoints());
        verify(growthService, never()).addGrowth(any());
    }

    @Test
    void sign_昨天已签连签3天_第4天得20积分() {
        when(userMapper.selectById(UID)).thenReturn(user(TODAY.minusDays(1), 3));
        when(signInMapper.selectOne(any())).thenReturn(null);
        when(userMapper.applySignIn(UID, TODAY, 4)).thenReturn(1);

        SignInResult result = signInService.sign(UID, TODAY);

        assertEquals(4, result.getContinuousDays());
        assertEquals(20L, result.getPointsEarned());
    }

    @Test
    void sign_上次签到为一周前_连签中断重置为1() {
        when(userMapper.selectById(UID)).thenReturn(user(TODAY.minusDays(6), 7));
        when(signInMapper.selectOne(any())).thenReturn(null);
        when(userMapper.applySignIn(UID, TODAY, 1)).thenReturn(1);

        SignInResult result = signInService.sign(UID, TODAY);

        assertEquals(1, result.getContinuousDays());
        assertEquals(5L, result.getPointsEarned());
        verify(growthService, never()).addGrowth(any());
    }

    @Test
    void sign_连续第7天_50积分并加50成长值() {
        when(userMapper.selectById(UID)).thenReturn(user(TODAY.minusDays(1), 6));
        when(signInMapper.selectOne(any())).thenReturn(null);
        when(userMapper.applySignIn(UID, TODAY, 7)).thenReturn(1);

        SignInResult result = signInService.sign(UID, TODAY);

        assertEquals(7, result.getContinuousDays());
        assertEquals(50L, result.getPointsEarned());
        assertEquals(50, result.getGrowthEarned());

        ArgumentCaptor<GrowthCommand> captor = ArgumentCaptor.forClass(GrowthCommand.class);
        verify(growthService).addGrowth(captor.capture());
        assertEquals(50, captor.getValue().getGrowth());
        assertEquals("SIGNW:" + UID + ":2026-09-16", captor.getValue().getBizNo());
    }

    @Test
    void sign_连续第8天_50积分但无里程碑成长值() {
        when(userMapper.selectById(UID)).thenReturn(user(TODAY.minusDays(1), 7));
        when(signInMapper.selectOne(any())).thenReturn(null);
        when(userMapper.applySignIn(UID, TODAY, 8)).thenReturn(1);

        SignInResult result = signInService.sign(UID, TODAY);

        assertEquals(8, result.getContinuousDays());
        assertEquals(50L, result.getPointsEarned());
        assertEquals(0, result.getGrowthEarned());
        verify(growthService, never()).addGrowth(any());
    }

    @Test
    void sign_当日重复签到_幂等返回已有结果不重复发积分() {
        UserSignIn exist = new UserSignIn();
        exist.setUserId(UID);
        exist.setSignDate(TODAY);
        exist.setContinuousDays(2);
        exist.setPointsEarned(10L);
        exist.setGrowthEarned(0);
        when(userMapper.selectById(UID)).thenReturn(user(TODAY.minusDays(1), 1));
        when(signInMapper.selectOne(any())).thenReturn(exist);

        SignInResult result = signInService.sign(UID, TODAY);

        assertEquals(2, result.getContinuousDays());
        assertEquals(10L, result.getPointsEarned());
        verify(userMapper, never()).applySignIn(any(), any(), any());
        verify(accountService, never()).grantPoints(any());
        verify(growthService, never()).addGrowth(any());
    }

    @Test
    void sign_并发条件更新0行_回查已有记录幂等返回() {
        UserSignIn concurrent = new UserSignIn();
        concurrent.setUserId(UID);
        concurrent.setSignDate(TODAY);
        concurrent.setContinuousDays(5);
        concurrent.setPointsEarned(25L);
        concurrent.setGrowthEarned(0);
        when(userMapper.selectById(UID)).thenReturn(user(TODAY.minusDays(1), 4));
        when(signInMapper.selectOne(any())).thenReturn(null, concurrent);
        when(userMapper.applySignIn(UID, TODAY, 5)).thenReturn(0);

        SignInResult result = signInService.sign(UID, TODAY);

        assertEquals(5, result.getContinuousDays());
        assertEquals(25L, result.getPointsEarned());
        verify(accountService, never()).grantPoints(any());
    }

    @Test
    void sign_用户不存在_抛NotFound() {
        when(userMapper.selectById(404L)).thenReturn(null);
        assertThrows(BizException.class, () -> signInService.sign(404L, TODAY));
        verify(accountService, never()).grantPoints(any());
    }
}
