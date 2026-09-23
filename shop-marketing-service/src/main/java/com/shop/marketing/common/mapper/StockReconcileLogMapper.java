package com.shop.marketing.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.common.entity.StockReconcileLog;
import org.apache.ibatis.annotations.Mapper;

/** W4-4/P1-2 对账自愈留痕 Mapper。 */
@Mapper
public interface StockReconcileLogMapper extends BaseMapper<StockReconcileLog> {
}
