package com.shop.order.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.order.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** B1：按团号捞全团订单（拼团事件消费不依赖事件体 orderNo 覆盖全团）。 */
    @Select("SELECT * FROM t_order_order WHERE group_no = #{groupNo} AND deleted = 0")
    List<Order> selectByGroupNo(@Param("groupNo") String groupNo);

    /**
     * B1：成团后续期待付款单支付截止时间（CAS 只宽不窄）。
     * 仅命中 {@code status=10 且 expire_time < deadline} 的行，影响行数返回供调用方判定/投递延时。
     */
    @Update("UPDATE t_order_order SET expire_time = #{deadline}, version = version + 1 "
            + "WHERE group_no = #{groupNo} AND status = 10 AND expire_time < #{deadline} AND deleted = 0")
    int batchUpdateExpireForUnpaid(@Param("groupNo") String groupNo,
                                   @Param("deadline") LocalDateTime deadline);

    /** 订单状态条件推进（状态机落库：影响 0 行即并发冲突/非法状态）。 */
    @Update("UPDATE t_order_order SET status = #{toStatus}, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND user_id = #{userId} AND status = #{fromStatus} AND deleted = 0")
    int updateStatus(@Param("orderNo") String orderNo, @Param("userId") Long userId,
                     @Param("fromStatus") int fromStatus, @Param("toStatus") int toStatus);

    /** 不限用户的状态条件推进（商户发货/系统任务/事件消费使用）。 */
    @Update("UPDATE t_order_order SET status = #{toStatus}, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status = #{fromStatus} AND deleted = 0")
    int updateStatusByNo(@Param("orderNo") String orderNo,
                         @Param("fromStatus") int fromStatus, @Param("toStatus") int toStatus);

    /**
     * 支付成功回写：10 待付款 → 20 待发货（ORDER_PAID 消费，条件更新保证幂等）。
     */
    @Update("UPDATE t_order_order SET status = 20, pay_method = #{payMethod}, pay_no = #{payNo}, "
            + "pay_transaction_no = #{transactionNo}, pay_time = #{payTime}, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status = 10 AND deleted = 0")
    int markPaid(@Param("orderNo") String orderNo, @Param("payMethod") Integer payMethod,
                 @Param("payNo") String payNo, @Param("transactionNo") String transactionNo,
                 @Param("payTime") LocalDateTime payTime);

    /** 用户/超时/商家取消：10 → 50。 */
    @Update("UPDATE t_order_order SET status = 50, cancel_type = #{cancelType}, cancel_time = #{cancelTime}, "
            + "version = version + 1 WHERE order_no = #{orderNo} AND status = 10 AND deleted = 0")
    int markCancelled(@Param("orderNo") String orderNo, @Param("cancelType") int cancelType,
                      @Param("cancelTime") LocalDateTime cancelTime);

    /** 商家发货：20 → 30，写物流与自动确认截止。 */
    @Update("UPDATE t_order_order SET status = 30, logistics_no = #{logisticsNo}, "
            + "logistics_company = #{logisticsCompany}, ship_time = #{shipTime}, "
            + "auto_confirm_deadline = #{deadline}, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status = 20 AND deleted = 0")
    int markShipped(@Param("orderNo") String orderNo, @Param("logisticsNo") String logisticsNo,
                    @Param("logisticsCompany") String logisticsCompany,
                    @Param("shipTime") LocalDateTime shipTime, @Param("deadline") LocalDateTime deadline);

    /** 确认收货：30 → 40，写确认时间与售后期截止（+15 天）。 */
    @Update("UPDATE t_order_order SET status = 40, confirm_time = #{confirmTime}, "
            + "aftersale_deadline = #{aftersaleDeadline}, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status = 30 AND deleted = 0")
    int markConfirmed(@Param("orderNo") String orderNo, @Param("confirmTime") LocalDateTime confirmTime,
                      @Param("aftersaleDeadline") LocalDateTime aftersaleDeadline);

    /** 售后期满关闭：40 → 70。 */
    @Update("UPDATE t_order_order SET status = 70, complete_time = #{completeTime}, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status = 40 AND deleted = 0")
    int markClosed(@Param("orderNo") String orderNo, @Param("completeTime") LocalDateTime completeTime);

    /** 进入售后态：记录原状态并推进到 60/61/62（仅 20/30/40 可进入）。 */
    @Update("UPDATE t_order_order SET status = #{toStatus}, pre_aftersale_status = status, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status IN (20,30,40) AND deleted = 0")
    int enterAftersale(@Param("orderNo") String orderNo, @Param("toStatus") int toStatus);

    /** 售后终结恢复进入前状态（如部分退款/换货完成继续履约）。 */
    @Update("UPDATE t_order_order SET status = pre_aftersale_status, pre_aftersale_status = NULL, "
            + "version = version + 1 WHERE order_no = #{orderNo} AND status = #{fromStatus} AND deleted = 0")
    int resumeAftersale(@Param("orderNo") String orderNo, @Param("fromStatus") int fromStatus);

    /** 售后态内部迁移（如退款中→退货退款中），保留原状态快照。 */
    @Update("UPDATE t_order_order SET status = #{toStatus}, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status IN (60,61,62) AND deleted = 0")
    int moveWithinAftersale(@Param("orderNo") String orderNo, @Param("toStatus") int toStatus);

    /** 售后终结恢复（不校验当前售后子状态）。 */
    @Update("UPDATE t_order_order SET status = pre_aftersale_status, pre_aftersale_status = NULL, "
            + "version = version + 1 WHERE order_no = #{orderNo} AND status IN (60,61,62) "
            + "AND pre_aftersale_status IS NOT NULL AND deleted = 0")
    int resumeAftersaleByNo(@Param("orderNo") String orderNo);

    /**
     * 售后终结整单关闭（全额退款）：20/60/61/62 → 70。
     *
     * <p>status=20（待发货）分支：拼团失败自动退款不经售后态——{@code GroupbuyOrderFlowServiceImpl}
     * 对已付待发货单直接发起 FULL 退款（refundNo=GB:{orderNo}，无 aftersaleNo），REFUND_SUCCESS
     * 到达时订单仍在 20，必须能关单。正常售后退款链路必先转 60/61 再退款，故 20+FULL+无售后单
     * 只会来自拼团失败这一条编排，扩展谓词不产生误关单；待收货(30)/已完成(40) 的全额退款必须
     * 先进售后态，刻意不纳入。
     */
    @Update("UPDATE t_order_order SET status = 70, complete_time = #{completeTime}, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status IN (20,60,61,62) AND deleted = 0")
    int closeAfterAftersale(@Param("orderNo") String orderNo, @Param("completeTime") LocalDateTime completeTime);

    /** 待发货修改收货地址快照。 */
    @Update("UPDATE t_order_order SET receiver = #{receiver}, receiver_phone = #{phone}, province = #{province}, "
            + "city = #{city}, district = #{district}, detail_address = #{detailAddress}, address_id = #{addressId}, "
            + "version = version + 1 WHERE order_no = #{orderNo} AND status = 20 AND deleted = 0")
    int updateAddress(@Param("orderNo") String orderNo, @Param("receiver") String receiver,
                      @Param("phone") String phone, @Param("province") String province,
                      @Param("city") String city, @Param("district") String district,
                      @Param("detailAddress") String detailAddress, @Param("addressId") Long addressId);

    /** 提醒发货标记（仅待发货单，幂等覆盖最近时间）。 */
    @Update("UPDATE t_order_order SET remind_time = #{remindTime} "
            + "WHERE order_no = #{orderNo} AND status = 20 AND deleted = 0")
    int markRemind(@Param("orderNo") String orderNo, @Param("remindTime") LocalDateTime remindTime);
}
