package com.shop.settlement.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.account.entity.SettAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 资金账户 Mapper：所有余额变动均为行锁条件更新，影响 0 行抛业务异常。
 */
@Mapper
public interface AccountMapper extends BaseMapper<SettAccount> {

    /** 增加可用余额（平台佣金/技费/通道费入账、营销补贴冲正退回等系统账户入账）。 */
    @Update("UPDATE t_sett_account SET available_fen = available_fen + #{amount}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE owner_id = #{ownerId} AND role_type = #{roleType} AND deleted = 0")
    int creditAvailable(@Param("ownerId") long ownerId, @Param("roleType") int roleType,
                        @Param("amount") long amount);

    /** 扣减可用余额（系统账户出账，如营销补贴），允许系统账户为负，不设余额下限。 */
    @Update("UPDATE t_sett_account SET available_fen = available_fen - #{amount}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE owner_id = #{ownerId} AND role_type = #{roleType} AND deleted = 0")
    int debitAvailable(@Param("ownerId") long ownerId, @Param("roleType") int roleType,
                       @Param("amount") long amount);

    /** 增加待结算余额（清算货款入账）。 */
    @Update("UPDATE t_sett_account SET pending_settle_fen = pending_settle_fen + #{amount}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE owner_id = #{ownerId} AND role_type = #{roleType} AND deleted = 0")
    int creditPending(@Param("ownerId") long ownerId, @Param("roleType") int roleType,
                      @Param("amount") long amount);

    /**
     * 原子部分扣减待结算（P1-12 退款瀑布第一档）：
     * 单条 SQL 用 LEAST 按当前行值扣减 min(need, pending)，不依赖任何事先 SELECT 读到的值；
     * 行 X 锁随 UPDATE 获取并持有到事务提交，并发两笔退款串行化，不会多扣/漏扣。
     * 返回影响行数：1=已扣（可能部分扣减，实际扣减值由服务层在同一行锁内计算），0=待结算为 0 无款可扣。
     */
    @Update("UPDATE t_sett_account SET pending_settle_fen = pending_settle_fen - "
            + "LEAST(#{need}, pending_settle_fen), "
            + "version = version + 1, update_time = NOW() "
            + "WHERE owner_id = #{ownerId} AND role_type = #{roleType} AND deleted = 0 "
            + "AND pending_settle_fen > 0")
    int debitPendingPartial(@Param("ownerId") long ownerId, @Param("roleType") int roleType,
                            @Param("need") long need);

    /**
     * 原子部分扣减可提现余额（P1-10 退款瀑布第二档）：语义同 {@link #debitPendingPartial}，
     * 扣 min(need, available)。
     */
    @Update("UPDATE t_sett_account SET available_fen = available_fen - "
            + "LEAST(#{need}, available_fen), "
            + "version = version + 1, update_time = NOW() "
            + "WHERE owner_id = #{ownerId} AND role_type = #{roleType} AND deleted = 0 "
            + "AND available_fen > 0")
    int debitAvailablePartial(@Param("ownerId") long ownerId, @Param("roleType") int roleType,
                              @Param("need") long need);

    /**
     * 行锁读取（P1-12）：{@code SELECT ... FOR UPDATE} 取账户行当前值并持有 X 锁到提交。
     * 服务层固定序列：先本方法锁内取扣减前余额 → 再执行 LEAST 原子扣减 SQL；
     * 与旧实现「普通快照 SELECT + 按读到值做条件更新」不同：行锁把同账户并发扣减串行化，
     * LEAST 按行内当前值计算，不把任何读到的余额拼进 SQL。
     */
    @Select("SELECT * FROM t_sett_account WHERE owner_id = #{ownerId} "
            + "AND role_type = #{roleType} AND deleted = 0 FOR UPDATE")
    SettAccount selectForUpdate(@Param("ownerId") long ownerId, @Param("roleType") int roleType);

    /** 待结算转可提现（日终批），条件：待结算余额充足。 */
    @Update("UPDATE t_sett_account SET pending_settle_fen = pending_settle_fen - #{amount}, "
            + "available_fen = available_fen + #{amount}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE owner_id = #{ownerId} AND role_type = #{roleType} AND deleted = 0 "
            + "AND pending_settle_fen >= #{amount}")
    int pendingToAvailable(@Param("ownerId") long ownerId, @Param("roleType") int roleType,
                           @Param("amount") long amount);

    /** 提现冻结：可用→冻结，条件：可用余额充足。 */
    @Update("UPDATE t_sett_account SET available_fen = available_fen - #{amount}, "
            + "frozen_fen = frozen_fen + #{amount}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE owner_id = #{ownerId} AND role_type = #{roleType} AND deleted = 0 "
            + "AND available_fen >= #{amount}")
    int freeze(@Param("ownerId") long ownerId, @Param("roleType") int roleType,
               @Param("amount") long amount);

    /** 提现成功出款：冻结扣减，条件：冻结余额充足。 */
    @Update("UPDATE t_sett_account SET frozen_fen = frozen_fen - #{amount}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE owner_id = #{ownerId} AND role_type = #{roleType} AND deleted = 0 "
            + "AND frozen_fen >= #{amount}")
    int unfreezeOut(@Param("ownerId") long ownerId, @Param("roleType") int roleType,
                    @Param("amount") long amount);

    /** 提现失败/拒绝退回：冻结→可用，条件：冻结余额充足。 */
    @Update("UPDATE t_sett_account SET frozen_fen = frozen_fen - #{amount}, "
            + "available_fen = available_fen + #{amount}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE owner_id = #{ownerId} AND role_type = #{roleType} AND deleted = 0 "
            + "AND frozen_fen >= #{amount}")
    int unfreezeBack(@Param("ownerId") long ownerId, @Param("roleType") int roleType,
                     @Param("amount") long amount);
}
