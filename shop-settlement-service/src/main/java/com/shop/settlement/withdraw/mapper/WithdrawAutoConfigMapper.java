package com.shop.settlement.withdraw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.withdraw.entity.SettWithdrawAutoConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 自动提现配置 Mapper。 */
@Mapper
public interface WithdrawAutoConfigMapper extends BaseMapper<SettWithdrawAutoConfig> {

    /** 查询全部启用的自动提现配置（ShedLock 单实例执行，商户量可控）。 */
    @Select("SELECT * FROM t_sett_withdraw_auto_config WHERE deleted = 0 AND enabled = 1")
    List<SettWithdrawAutoConfig> selectEnabled();
}
