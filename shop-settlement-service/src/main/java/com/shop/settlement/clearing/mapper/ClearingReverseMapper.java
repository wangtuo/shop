package com.shop.settlement.clearing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.settlement.clearing.entity.SettClearingReverse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 清算冲正明细 Mapper，refund_no 唯一幂等。 */
@Mapper
public interface ClearingReverseMapper extends BaseMapper<SettClearingReverse> {

    /**
     * P0-1 穿仓扫表兜底：分页取 status=2（V4 idx_status）且 create_time&lt;=before 的挂起冲正。
     * NOT EXISTS 排除已开缺口工单的冲正——固定 (before, limit) 签名下，调用方逐页处理落单后
     * 下一次查询自然推进到未处理行，直到返回不足一页；非 status=2 不会被扫到。
     */
    @Select("SELECT r.* FROM t_sett_clearing_reverse r WHERE r.status = 2 AND r.deleted = 0 "
            + "AND r.create_time <= #{before} "
            + "AND NOT EXISTS (SELECT 1 FROM t_sett_shortfall_workorder w "
            + "WHERE w.reverse_no = r.reverse_no AND w.deleted = 0) "
            + "ORDER BY r.id ASC LIMIT #{limit}")
    List<SettClearingReverse> selectSuspended(@Param("before") LocalDateTime before,
                                              @Param("limit") int limit);
}
