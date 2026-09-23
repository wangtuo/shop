package com.shop.marketing.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.activity.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 单据状态 CAS（R4-25：一订单可含多 SKU，每 SKU 一行，本语句一次翻转该单全部行）：
     * 仅当前 status = from 时置为 to。
     * @return 被翻转的行数；0 全部行已被并发推进/终态，调用方放弃
     */
    @Update("UPDATE t_seckill_order SET status = #{toStatus} "
            + "WHERE order_no = #{orderNo} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("orderNo") String orderNo, @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus);

    /** 一个秒杀订单的全部 SKU 行（R4-25：确认/释放逐 SKU 扣回补，不能只处理首行）。 */
    @Select("SELECT * FROM t_seckill_order WHERE order_no = #{orderNo} AND deleted = 0")
    List<SeckillOrder> selectListByOrderNo(@Param("orderNo") String orderNo);
}
