package com.shop.user.signin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.GrowthCommand;
import com.shop.api.user.enums.GrowthScene;
import com.shop.api.user.enums.PointsScene;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.user.account.entity.UserSignIn;
import com.shop.user.account.mapper.UserSignInMapper;
import com.shop.user.account.service.AccountService;
import com.shop.user.account.service.GrowthService;
import com.shop.user.member.PointsCalc;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import com.shop.user.signin.dto.SignInResult;
import com.shop.user.signin.service.SignInService;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * 签到服务实现。
 *
 * <p>连续规则：昨天有签到记录则连签 +1，否则重置为 1；当日重复签到幂等返回。
 * 积分 5/10/15/20/25/30/50（之后每天 50），经 grantPoints 的每日上限（50）二次兜底；
 * 连续第 7/14/21… 天额外 +50 成长值。Redisson 用户锁 + 签到表 UK(user_id, sign_date) 双保险。
 */
@Service
public class SignInServiceImpl implements SignInService {

    private final DistributedLockTemplate lockTemplate;
    private final UserMapper userMapper;
    private final UserSignInMapper signInMapper;
    private final AccountService accountService;
    private final GrowthService growthService;
    // 自注入代理：sign(Long) 入口必须经代理调用带 @Transactional 的 sign(Long,LocalDate)，
    // 否则 this 自调用绕过事务（历史缺陷：签到记录/积分/成长值多表写入实际自动提交）。
    // @Lazy 打破构造期自引用循环。
    private final SignInService self;

    public SignInServiceImpl(DistributedLockTemplate lockTemplate,
                             UserMapper userMapper,
                             UserSignInMapper signInMapper,
                             AccountService accountService,
                             GrowthService growthService,
                             @Lazy SignInService self) {
        this.lockTemplate = lockTemplate;
        this.userMapper = userMapper;
        this.signInMapper = signInMapper;
        this.accountService = accountService;
        this.growthService = growthService;
        this.self = self;
    }

    @Override
    public SignInResult sign(Long userId) {
        return self.sign(userId, LocalDate.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SignInResult sign(Long userId, LocalDate today) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (today == null) {
            today = LocalDate.now();
        }
        LocalDate signDate = today;
        return lockTemplate.execute("user:sign:" + userId, (Supplier<SignInResult>) () -> doSign(userId, signDate));
    }

    private SignInResult doSign(Long userId, LocalDate today) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + userId);
        }
        UserSignIn exist = signInMapper.selectOne(new LambdaQueryWrapper<UserSignIn>()
                .eq(UserSignIn::getUserId, userId)
                .eq(UserSignIn::getSignDate, today));
        if (exist != null) {
            return toResult(exist);
        }

        LocalDate last = user.getLastSignDate();
        int days;
        if (today.minusDays(1).equals(last)) {
            days = user.getContinuousDays() == null ? 1 : user.getContinuousDays() + 1;
        } else {
            days = 1;
        }

        int rows = userMapper.applySignIn(userId, today, days);
        if (rows == 0) {
            // 并发下今日已被签到：回查返回，保证幂等
            UserSignIn concurrent = signInMapper.selectOne(new LambdaQueryWrapper<UserSignIn>()
                    .eq(UserSignIn::getUserId, userId)
                    .eq(UserSignIn::getSignDate, today));
            if (concurrent != null) {
                return toResult(concurrent);
            }
            throw new BizException(ErrorCode.CONFLICT, "签到状态冲突，请稍后重试");
        }

        long points = PointsCalc.signPoints(days);
        int growth = PointsCalc.isWeeklyMilestone(days) ? 50 : 0;

        UserSignIn record = new UserSignIn();
        record.setUserId(userId);
        record.setSignDate(today);
        record.setContinuousDays(days);
        record.setPointsEarned(points);
        record.setGrowthEarned(growth);
        try {
            signInMapper.insert(record);
        } catch (DuplicateKeyException e) {
            UserSignIn concurrent = signInMapper.selectOne(new LambdaQueryWrapper<UserSignIn>()
                    .eq(UserSignIn::getUserId, userId)
                    .eq(UserSignIn::getSignDate, today));
            return toResult(concurrent);
        }

        // 积分（bizNo 幂等；签到每日上限 50 在账户服务兜底）
        accountService.grantPoints(GrantPointsCommand.builder()
                .userId(userId)
                .bizNo(signPointsBiz(userId, today))
                .points(points)
                .scene(PointsScene.SIGN)
                .build());
        // 连续 7 天里程碑成长值
        if (growth > 0) {
            growthService.addGrowth(GrowthCommand.builder()
                    .userId(userId)
                    .bizNo(signGrowthBiz(userId, today))
                    .growth(growth)
                    .scene(GrowthScene.SIGN_WEEK)
                    .build());
        }
        return SignInResult.builder()
                .signDate(today)
                .continuousDays(days)
                .pointsEarned(points)
                .growthEarned(growth)
                .build();
    }

    private SignInResult toResult(UserSignIn record) {
        return SignInResult.builder()
                .signDate(record.getSignDate())
                .continuousDays(record.getContinuousDays())
                .pointsEarned(record.getPointsEarned())
                .growthEarned(record.getGrowthEarned())
                .build();
    }

    private String signPointsBiz(Long userId, LocalDate date) {
        return "SIGN:" + userId + ":" + date;
    }

    private String signGrowthBiz(Long userId, LocalDate date) {
        return "SIGNW:" + userId + ":" + date;
    }
}
