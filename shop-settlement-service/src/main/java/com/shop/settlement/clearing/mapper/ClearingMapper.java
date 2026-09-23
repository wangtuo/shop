package com.shop.settlement.clearing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.clearing.entity.SettClearing;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/** 清算单 Mapper。 */
@Mapper
public interface ClearingMapper extends BaseMapper<SettClearing> {

    /**
     * 日终批分页扫描：stage=20 且 due_date &lt;= 今日，按 id 游标翻页（每批 500，2h 目标）。
     */
    @Select("SELECT * FROM t_sett_clearing WHERE deleted = 0 AND stage = 20 "
            + "AND due_date IS NOT NULL AND due_date <= #{today} AND id > #{lastId} "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<SettClearing> selectDuePage(@Param("lastId") long lastId,
                                     @Param("today") LocalDate today,
                                     @Param("limit") int limit);
}
