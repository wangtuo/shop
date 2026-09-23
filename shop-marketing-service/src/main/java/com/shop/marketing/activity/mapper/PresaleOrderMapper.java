package com.shop.marketing.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.activity.entity.PresaleOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PresaleOrderMapper extends BaseMapper<PresaleOrder> {

    /** 尾款支付：定金已付 → 尾款已付。 */
    @Update("UPDATE t_presale_order SET status = 1, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status = 0 AND deleted = 0")
    int markFinalPaid(@Param("orderNo") String orderNo);

    /** 尾款窗口超时：0 → 2 已取消（定金不退）。 */
    @Update("UPDATE t_presale_order SET status = 2, version = version + 1 "
            + "WHERE status = 0 AND final_end_time < #{now} AND deleted = 0")
    int markTimeout(@Param("now") LocalDateTime now);

    /** 单条尾款超时取消（延时消息驱动，按 orderNo 条件更新）。 */
    @Update("UPDATE t_presale_order SET status = 2, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status = 0 AND final_end_time < #{now} AND deleted = 0")
    int markTimeoutByOrderNo(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);
}
