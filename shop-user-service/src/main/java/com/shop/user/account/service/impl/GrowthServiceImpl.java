package com.shop.user.account.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.api.user.dto.GrowthCommand;
import com.shop.api.user.dto.UserLevelDTO;
import com.shop.api.user.enums.MemberLevels;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.user.account.entity.UserGrowthFlow;
import com.shop.user.account.mapper.UserGrowthFlowMapper;
import com.shop.user.account.service.GrowthService;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GrowthServiceImpl implements GrowthService {

    private static final Logger log = LoggerFactory.getLogger(GrowthServiceImpl.class);
    private static final int PAGE_SIZE = 200;

    private final UserMapper userMapper;
    private final UserGrowthFlowMapper growthFlowMapper;
    private final GrowthDiscountExecutor discountExecutor;
    private final IdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addGrowth(GrowthCommand cmd) {
        if (cmd == null || cmd.getUserId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户ID不能为空");
        }
        if (cmd.getBizNo() == null || cmd.getBizNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "业务单号不能为空");
        }
        if (cmd.getGrowth() == null || cmd.getGrowth() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "成长值必须为正数");
        }
        if (growthFlowMapper.selectCount(new LambdaQueryWrapper<UserGrowthFlow>()
                .eq(UserGrowthFlow::getBizNo, cmd.getBizNo())) > 0) {
            return;
        }
        User user = requireUser(cmd.getUserId());
        long newGrowth = user.getGrowth() + cmd.getGrowth();
        int newLevel = MemberLevels.ofGrowth(newGrowth);

        UserGrowthFlow flow = new UserGrowthFlow();
        flow.setId(idGenerator.nextId());
        flow.setUserId(cmd.getUserId());
        flow.setBizNo(cmd.getBizNo());
        flow.setScene(cmd.getScene());
        flow.setGrowth(cmd.getGrowth());
        flow.setGrowthAfter(newGrowth);
        flow.setLevelAfter(newLevel);
        try {
            growthFlowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            log.info("成长值流水业务单号已存在，幂等返回 bizNo={}", cmd.getBizNo());
            return;
        }
        userMapper.updateGrowth(cmd.getUserId(), newGrowth, newLevel);
    }

    @Override
    public UserLevelDTO getLevel(Long userId) {
        User user = requireUser(userId);
        return UserLevelDTO.builder()
                .userId(userId)
                .level(user.getLevel())
                .levelName(MemberLevels.nameOf(user.getLevel()))
                .growth(user.getGrowth())
                .discount(MemberLevels.discountOf(user.getLevel()))
                .pointsRate(MemberLevels.pointsRateOf(user.getLevel()))
                .build();
    }

    @Override
    public int yearEndDiscount(int year) {
        int affected = 0;
        long pageNum = 1;
        while (true) {
            Page<User> page = userMapper.selectPage(new Page<>(pageNum, PAGE_SIZE),
                    new LambdaQueryWrapper<User>().orderByAsc(User::getId));
            List<User> users = page.getRecords();
            if (users.isEmpty()) {
                break;
            }
            for (User user : users) {
                if (discountExecutor.discount(user, year)) {
                    affected++;
                }
            }
            if (pageNum * PAGE_SIZE >= page.getTotal()) {
                break;
            }
            pageNum++;
        }
        log.info("年末成长值折算完成 year={} affected={}", year, affected);
        return affected;
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + userId);
        }
        return user;
    }
}
