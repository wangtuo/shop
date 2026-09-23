package com.shop.user.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.user.account.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    /** 按用户+账户类型查询（含逻辑删除过滤） */
    @Select("SELECT * FROM t_user_account WHERE user_id = #{userId} AND account_type = #{accountType} AND deleted = 0")
    UserAccount selectByUserType(@Param("userId") Long userId, @Param("accountType") Integer accountType);

    /** 借（扣减）余额/赠金，行锁条件更新防透支 */
    @Update("UPDATE t_user_account SET balance = balance - #{amount}, version = version + 1 "
            + "WHERE user_id = #{userId} AND account_type = #{accountType} AND deleted = 0 AND balance >= #{amount}")
    int debit(@Param("userId") Long userId,
              @Param("accountType") Integer accountType,
              @Param("amount") Long amount);

    /** 贷（入账）余额/积分可用余额 */
    @Update("UPDATE t_user_account SET balance = balance + #{amount}, version = version + 1 "
            + "WHERE user_id = #{userId} AND account_type = #{accountType} AND deleted = 0")
    int credit(@Param("userId") Long userId,
               @Param("accountType") Integer accountType,
               @Param("amount") Long amount);

    /** 积分冻结：可用 -> 冻结，可用余额不足影响 0 行 */
    @Update("UPDATE t_user_account SET balance = balance - #{points}, frozen = frozen + #{points}, version = version + 1 "
            + "WHERE user_id = #{userId} AND account_type = 3 AND deleted = 0 AND balance >= #{points}")
    int freezePoints(@Param("userId") Long userId, @Param("points") Long points);

    /** 积分实扣：冻结额核销（可用已在冻结时扣减） */
    @Update("UPDATE t_user_account SET frozen = frozen - #{points}, version = version + 1 "
            + "WHERE user_id = #{userId} AND account_type = 3 AND deleted = 0 AND frozen >= #{points}")
    int deductFrozen(@Param("userId") Long userId, @Param("points") Long points);

    /** 释放冻结：冻结退回可用 */
    @Update("UPDATE t_user_account SET frozen = frozen - #{points}, balance = balance + #{points}, version = version + 1 "
            + "WHERE user_id = #{userId} AND account_type = 3 AND deleted = 0 AND frozen >= #{points}")
    int releaseFrozen(@Param("userId") Long userId, @Param("points") Long points);

    /** 过期清零：按到期批次剩余合计扣减可用积分 */
    @Update("UPDATE t_user_account SET balance = balance - #{points}, version = version + 1 "
            + "WHERE user_id = #{userId} AND account_type = 3 AND deleted = 0 AND balance >= #{points}")
    int expirePoints(@Param("userId") Long userId, @Param("points") Long points);
}
