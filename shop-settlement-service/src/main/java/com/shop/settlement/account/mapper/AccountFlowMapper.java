package com.shop.settlement.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.account.entity.SettAccountFlow;
import org.apache.ibatis.annotations.Mapper;

/** 账户流水 Mapper。(biz_no, change_type) 唯一索引保证业务幂等。 */
@Mapper
public interface AccountFlowMapper extends BaseMapper<SettAccountFlow> {
}
