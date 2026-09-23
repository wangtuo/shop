package com.shop.aftersale.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.aftersale.aftersale.entity.PriceProtectRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PriceProtectRecordMapper extends BaseMapper<PriceProtectRecord> {

    @Select("SELECT * FROM t_aftersale_price_protect WHERE order_no = #{orderNo} "
            + "AND status IN (20, 30) AND deleted = 0 LIMIT 1")
    PriceProtectRecord selectActiveByOrderNo(@Param("orderNo") String orderNo);
}
