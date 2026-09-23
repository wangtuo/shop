package com.shop.marketing.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.coupon.entity.UserCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /** 预核销：0 未使用 → 4 已锁定（带用户归属校验，影响 0 行即券不可用/并发冲突）。 */
    @Update("UPDATE t_user_coupon SET status = 4, order_no = #{orderNo}, lock_time = NOW(), version = version + 1 "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 0 AND deleted = 0")
    int lockCoupon(@Param("id") Long id, @Param("userId") Long userId, @Param("orderNo") String orderNo);

    /** 释放回滚：4 → 0。 */
    @Update("UPDATE t_user_coupon SET status = 0, order_no = NULL, lock_time = NULL, version = version + 1 "
            + "WHERE id = #{id} AND status = 4 AND order_no = #{orderNo} AND deleted = 0")
    int releaseCoupon(@Param("id") Long id, @Param("orderNo") String orderNo);

    /** 支付成功核销：4 → 1。 */
    @Update("UPDATE t_user_coupon SET status = 1, used_time = NOW(), version = version + 1 "
            + "WHERE id = #{id} AND status = 4 AND order_no = #{orderNo} AND deleted = 0")
    int useCoupon(@Param("id") Long id, @Param("orderNo") String orderNo);

    /** 过期扫描：未使用且超过有效期 → 已过期（锁定中的券不扫，由订单超时释放后再处理）。 */
    @Update("UPDATE t_user_coupon SET status = 2, version = version + 1 "
            + "WHERE status = 0 AND valid_end_time < #{now} AND deleted = 0")
    int expireUnused(@Param("now") LocalDateTime now);
}
