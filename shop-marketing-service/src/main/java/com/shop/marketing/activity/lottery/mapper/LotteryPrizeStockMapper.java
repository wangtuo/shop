package com.shop.marketing.activity.lottery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.activity.lottery.entity.LotteryPrizeStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LotteryPrizeStockMapper extends BaseMapper<LotteryPrizeStock> {

    /**
     * 占用一个奖品库存：total_stock=0 不限量；有量奖品条件更新防超发。
     * 返回 0 = 已罄（调用方降级重抽）。
     */
    @Update("UPDATE t_lottery_prize_stock SET issued_count = issued_count + 1 "
            + "WHERE id = #{id} AND deleted = 0 "
            + "AND (total_stock = 0 OR issued_count < total_stock)")
    int occupy(@Param("id") Long id);

    /** 抽奖落库/发奖失败补偿：回补一个已占库存（不低于 0）。 */
    @Update("UPDATE t_lottery_prize_stock SET issued_count = issued_count - 1 "
            + "WHERE id = #{id} AND deleted = 0 AND issued_count > 0")
    int releaseOne(@Param("id") Long id);
}
