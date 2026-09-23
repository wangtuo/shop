package com.shop.product.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.product.stock.entity.StockWarning;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存预警记录 Mapper。
 */
@Mapper
public interface StockWarningMapper extends BaseMapper<StockWarning> {
}
