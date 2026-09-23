package com.shop.pay.feature.recon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.pay.feature.recon.entity.ReconBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ReconBatchMapper extends BaseMapper<ReconBatch> {

    /** 差错全部处置完成（无 10/20 差异）：批次 20→30 并落 finishTime，CAS 防并发重入。 */
    @Update("UPDATE t_pay_recon_batch SET status = 30, finish_time = #{finishTime} "
            + "WHERE id = #{id} AND status = 20 AND deleted = 0")
    int markFinished(@Param("id") Long id, @Param("finishTime") LocalDateTime finishTime);
}
