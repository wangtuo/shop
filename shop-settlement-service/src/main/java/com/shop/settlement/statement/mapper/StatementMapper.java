package com.shop.settlement.statement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.statement.entity.SettStatement;
import org.apache.ibatis.annotations.Mapper;

/** 结算单 Mapper。 */
@Mapper
public interface StatementMapper extends BaseMapper<SettStatement> {
}
