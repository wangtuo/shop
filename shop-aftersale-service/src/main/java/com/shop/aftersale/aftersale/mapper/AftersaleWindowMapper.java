package com.shop.aftersale.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.aftersale.aftersale.entity.AftersaleWindow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AftersaleWindowMapper extends BaseMapper<AftersaleWindow> {

    @Select("SELECT * FROM t_aftersale_window WHERE order_no = #{orderNo} AND deleted = 0")
    AftersaleWindow selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 发货事件投影（不存在则插入）。
     *
     * <p>B11 防乱序覆盖：has_freight_insurance / premium_fen 用 GREATEST 单调并集——
     * 先 shipped(has=1) 后 confirmed(has=0/null) 或先 confirmed 后 shipped 都不会把 1/正保费
     * 覆盖回 0（两事件字段口径一致，仅在投影到达顺序上可能翻转）。
     */
    @Update("UPDATE t_aftersale_window SET order_status = #{orderStatus}, shipped_time = #{shippedTime}, "
            + "auto_confirm_deadline = #{autoConfirmDeadline}, order_type = #{orderType}, "
            + "has_freight_insurance = GREATEST(has_freight_insurance, #{hasFreightInsurance}), "
            + "premium_fen = GREATEST(premium_fen, #{premiumFen}), update_time = NOW() "
            + "WHERE order_no = #{orderNo} AND deleted = 0")
    int updateShipped(@Param("orderNo") String orderNo,
                      @Param("orderStatus") int orderStatus,
                      @Param("orderType") int orderType,
                      @Param("shippedTime") java.time.LocalDateTime shippedTime,
                      @Param("autoConfirmDeadline") java.time.LocalDateTime autoConfirmDeadline,
                      @Param("hasFreightInsurance") int hasFreightInsurance,
                      @Param("premiumFen") long premiumFen);

    /**
     * 确认收货事件投影。金额字段以 confirmed 为准（仅 confirmed 携带完整金额）；
     * 运费险两列同样 GREATEST 单调并集，防止与 shipped 乱序时把购险标记覆盖回 0。
     */
    @Update("UPDATE t_aftersale_window SET order_status = #{orderStatus}, merchant_id = #{merchantId}, "
            + "confirm_time = #{confirmTime}, free_aftersale_deadline = #{freeAftersaleDeadline}, "
            + "warranty_days = #{warrantyDays}, warranty_deadline = #{warrantyDeadline}, "
            + "product_pay_fen = #{productPayFen}, freight_fen = #{freightFen}, "
            + "used_points_fen = #{usedPointsFen}, "
            + "has_freight_insurance = GREATEST(has_freight_insurance, #{hasFreightInsurance}), "
            + "premium_fen = GREATEST(premium_fen, #{premiumFen}), update_time = NOW() "
            + "WHERE order_no = #{orderNo} AND deleted = 0")
    int updateConfirmed(@Param("orderNo") String orderNo,
                        @Param("orderStatus") int orderStatus,
                        @Param("merchantId") Long merchantId,
                        @Param("confirmTime") java.time.LocalDateTime confirmTime,
                        @Param("freeAftersaleDeadline") java.time.LocalDateTime freeAftersaleDeadline,
                        @Param("warrantyDays") int warrantyDays,
                        @Param("warrantyDeadline") java.time.LocalDateTime warrantyDeadline,
                        @Param("productPayFen") long productPayFen,
                        @Param("freightFen") long freightFen,
                        @Param("usedPointsFen") long usedPointsFen,
                        @Param("hasFreightInsurance") int hasFreightInsurance,
                        @Param("premiumFen") long premiumFen);
}
