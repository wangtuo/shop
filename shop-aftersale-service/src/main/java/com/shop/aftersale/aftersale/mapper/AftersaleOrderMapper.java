package com.shop.aftersale.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AftersaleOrderMapper extends BaseMapper<AftersaleOrder> {

    /** 状态条件推进；影响 0 行即状态不符/并发冲突。 */
    @Update("UPDATE t_aftersale_order SET status = #{toStatus}, version = version + 1, "
            + "update_time = NOW() WHERE aftersale_no = #{aftersaleNo} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("aftersaleNo") String aftersaleNo,
                     @Param("fromStatus") int fromStatus,
                     @Param("toStatus") int toStatus);

    @Select("SELECT * FROM t_aftersale_order WHERE aftersale_no = #{aftersaleNo} AND deleted = 0")
    AftersaleOrder selectByNo(@Param("aftersaleNo") String aftersaleNo);

    @Select("SELECT * FROM t_aftersale_order WHERE status = #{status} AND audit_deadline IS NOT NULL "
            + "AND audit_deadline <= #{now} AND deleted = 0 LIMIT #{limit}")
    List<AftersaleOrder> selectAuditTimeout(@Param("status") int status,
                                            @Param("now") LocalDateTime now,
                                            @Param("limit") int limit);

    @Select("SELECT * FROM t_aftersale_order WHERE status = 30 AND receive_deadline IS NOT NULL "
            + "AND receive_deadline <= #{now} AND deleted = 0 LIMIT #{limit}")
    List<AftersaleOrder> selectReceiveTimeout(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT * FROM t_aftersale_order WHERE status = 41 AND exchange_ship_deadline IS NOT NULL "
            + "AND exchange_ship_deadline <= #{now} AND deleted = 0 LIMIT #{limit}")
    List<AftersaleOrder> selectExchangeShipTimeout(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT COUNT(1) FROM t_aftersale_order WHERE order_no = #{orderNo} AND type = 5 "
            + "AND status IN (10, 40, 41, 42, 43, 50, 80) AND deleted = 0")
    int countPriceProtect(@Param("orderNo") String orderNo);

    /**
     * C8：商户 since 之后未终结售后单数（终态 50 已完成 / 55 已拒绝 / 90 已取消之外全部在途）。
     */
    @Select("SELECT COUNT(1) FROM t_aftersale_order WHERE merchant_id = #{merchantId} "
            + "AND status NOT IN (50, 55, 90) AND create_time >= #{since} AND deleted = 0")
    long countOpenByMerchantSince(@Param("merchantId") Long merchantId,
                                  @Param("since") LocalDateTime since);

    /** C8：商户 since 之后近期已终结售后单数（保证金判据快照的已终结侧）。 */
    @Select("SELECT COUNT(1) FROM t_aftersale_order WHERE merchant_id = #{merchantId} "
            + "AND status IN (50, 55, 90) AND create_time >= #{since} AND deleted = 0")
    long countFinishedByMerchantSince(@Param("merchantId") Long merchantId,
                                      @Param("since") LocalDateTime since);
}
