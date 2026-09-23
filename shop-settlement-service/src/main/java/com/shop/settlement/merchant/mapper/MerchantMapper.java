package com.shop.settlement.merchant.mapper;

import com.shop.settlement.merchant.entity.SettMerchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 商户 Mapper。
 */
@Mapper
public interface MerchantMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<SettMerchant> {

    /**
     * 保证金条件变动（行锁 + 余额非负约束），影响 0 行即余额不足等并发冲突。
     *
     * @param delta 变动额（分，正充值/负扣减）
     */
    @Update("UPDATE t_sett_merchant SET deposit_balance_fen = deposit_balance_fen + #{delta}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE id = #{merchantId} AND deleted = 0 AND deposit_balance_fen + #{delta} >= 0")
    int changeDeposit(@Param("merchantId") Long merchantId, @Param("delta") long delta);

    /**
     * 行锁读取商户（P1-10 退款瀑布保证金档）：固定序列
     * {@code SELECT ... FOR UPDATE} → {@link #changeDepositPartial} 原子扣减，
     * 同商户并发退款在 InnoDB 行锁上串行化。
     */
    @Select("SELECT * FROM t_sett_merchant WHERE id = #{merchantId} AND deleted = 0 FOR UPDATE")
    SettMerchant selectForUpdate(@Param("merchantId") Long merchantId);

    /**
     * 原子部分扣减保证金（P1-10 退款瀑布第三档）：扣 min(need, deposit)，
     * 余额清零也只影响 1 行；实际扣减值由服务层在同一行锁内计算，剩余缺口由调用方挂起追讨。
     */
    @Update("UPDATE t_sett_merchant SET deposit_balance_fen = deposit_balance_fen - "
            + "LEAST(#{need}, deposit_balance_fen), "
            + "version = version + 1, update_time = NOW() "
            + "WHERE id = #{merchantId} AND deleted = 0 AND deposit_balance_fen > 0")
    int changeDepositPartial(@Param("merchantId") Long merchantId, @Param("need") long need);
}
