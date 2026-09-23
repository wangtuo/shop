package com.shop.user.account.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.user.enums.MemberLevels;
import com.shop.framework.id.IdGenerator;
import com.shop.user.account.entity.UserGrowthDiscount;
import com.shop.user.account.entity.UserGrowthFlow;
import com.shop.user.account.mapper.UserGrowthDiscountMapper;
import com.shop.user.member.PointsCalc;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单用户年末成长值折算（独立 Bean，保证 {@link Transactional} 经代理生效）。
 * (user_id, year) 唯一记录 + 事务保证：本年仅执行一次，折算记录与用户等级更新原子提交。
 */
@Service
@RequiredArgsConstructor
public class GrowthDiscountExecutor {

    private static final Logger log = LoggerFactory.getLogger(GrowthDiscountExecutor.class);

    private final UserMapper userMapper;
    private final UserGrowthDiscountMapper discountMapper;
    private final IdGenerator idGenerator;

    /**
     * @return true 表示本次执行了折算；false 表示该用户本年已折算过（幂等跳过）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean discount(User user, int year) {
        if (discountMapper.selectCount(new LambdaQueryWrapper<UserGrowthDiscount>()
                .eq(UserGrowthDiscount::getUserId, user.getId())
                .eq(UserGrowthDiscount::getYear, year)) > 0) {
            return false;
        }
        int beforeLevel = user.getLevel();
        long beforeGrowth = user.getGrowth();
        long afterGrowth = PointsCalc.guaranteeLevelFloor(PointsCalc.discountGrowth(beforeGrowth), beforeLevel);
        int afterLevel = MemberLevels.ofGrowth(afterGrowth);
        if (afterLevel < beforeLevel) {
            // 理论上保底后不会降级，再托底一次
            afterGrowth = PointsCalc.guaranteeLevelFloor(afterGrowth, beforeLevel);
            afterLevel = beforeLevel;
        }
        UserGrowthDiscount record = new UserGrowthDiscount();
        record.setId(idGenerator.nextId());
        record.setUserId(user.getId());
        record.setYear(year);
        record.setBeforeGrowth(beforeGrowth);
        record.setAfterGrowth(afterGrowth);
        record.setBeforeLevel(beforeLevel);
        record.setAfterLevel(afterLevel);
        try {
            discountMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("年末折算记录已存在，幂等跳过 userId={} year={}", user.getId(), year);
            return false;
        }
        userMapper.updateGrowth(user.getId(), afterGrowth, afterLevel);
        return true;
    }
}
