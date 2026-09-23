package com.shop.settlement.withdraw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.withdraw.entity.SettWithdrawDailyCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 提现月免费次数 / 单日额度统计 Mapper。 */
@Mapper
public interface WithdrawDailyCountMapper extends BaseMapper<SettWithdrawDailyCount> {

    /**
     * 申请落账（存在则累加），与提现申请同事务。
     *
     * @param charged 本笔是否收费（0 免费 1 收费）
     */
    @Insert("INSERT INTO t_sett_withdraw_daily_count "
            + "(merchant_id, stat_month, apply_count, charged_count, daily_amount_fen, stat_date, "
            + "create_time, update_time, deleted) "
            + "VALUES (#{merchantId}, #{statMonth}, 1, #{charged}, #{amountFen}, #{statDate}, "
            + "NOW(), NOW(), 0) "
            + "ON DUPLICATE KEY UPDATE apply_count = apply_count + 1, "
            + "charged_count = charged_count + #{charged}, "
            + "daily_amount_fen = daily_amount_fen + #{amountFen}, update_time = NOW()")
    int bumpOnApply(@Param("merchantId") long merchantId,
                    @Param("statMonth") String statMonth,
                    @Param("statDate") java.time.LocalDate statDate,
                    @Param("amountFen") long amountFen,
                    @Param("charged") int charged);

    /** 汇总某自然月已申请笔数（跨日多行求和）。 */
    @Select("SELECT COALESCE(SUM(apply_count), 0) FROM t_sett_withdraw_daily_count "
            + "WHERE deleted = 0 AND merchant_id = #{merchantId} AND stat_month = #{statMonth}")
    int sumMonthApplyCount(@Param("merchantId") long merchantId,
                           @Param("statMonth") String statMonth);
}
