package com.shop.user.account.service;

import com.shop.api.user.dto.GrowthCommand;
import com.shop.api.user.dto.UserLevelDTO;

/**
 * 成长值/会员等级服务：成长值变更后重算等级；年末 80% 折算保底当前等级。
 */
public interface GrowthService {

    /** 增加成长值（bizNo 幂等），变更后按成长值重算会员等级 */
    void addGrowth(GrowthCommand cmd);

    /** 查询会员等级与权益（折扣、积分倍率） */
    UserLevelDTO getLevel(Long userId);

    /**
     * 年末成长值折算：按 80% 向下取整，保底当前等级下限（不降级）。
     * (userId, year) 唯一记录保证本年仅执行一次。
     *
     * @param year 折算年份（配置注入，便于单测）
     * @return 本次实际折算的用户数
     */
    int yearEndDiscount(int year);
}
