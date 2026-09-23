package com.shop.marketing.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.activity.entity.SeckillUserBuy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀每用户每场占件计数 Mapper（R4-25）。
 *
 * <p>限购强约束从 t_seckill_order 的 uk_activity_user 迁移为本表的<b>行锁条件占件</b>：
 * 条件更新 {@code total_qty + ? <= limit} 命中即原子占件，0 行即超限；
 * 计数行不存在时先插入（uk 并发互斥，负方重试条件更新）。</p>
 */
@Mapper
public interface SeckillUserBuyMapper extends BaseMapper<SeckillUserBuy> {

    /**
     * 原子占件：仅当累计后不超过限购时增加计数（行锁串行同 (activity,user) 并发下单）。
     *
     * @return 1 占件成功；0 超限（调用方拒绝并回补已预占库存）
     */
    @Update("UPDATE t_seckill_user_buy SET total_qty = total_qty + #{qty}, update_time = NOW() "
            + "WHERE activity_id = #{activityId} AND user_id = #{userId} AND deleted = 0 "
            + "AND total_qty + #{qty} <= #{limit}")
    int claim(@Param("activityId") Long activityId, @Param("userId") Long userId,
              @Param("qty") int qty, @Param("limit") int limit);

    /**
     * 取消/超时释放回减：释放的件数不再计入限购（口径同原 SUM(status IN 0,1)）。
     * GREATEST 兜底防止任何历史脏数据导致计数变负。
     */
    @Update("UPDATE t_seckill_user_buy SET total_qty = GREATEST(total_qty - #{qty}, 0), update_time = NOW() "
            + "WHERE activity_id = #{activityId} AND user_id = #{userId} AND deleted = 0")
    int releaseQty(@Param("activityId") Long activityId, @Param("userId") Long userId,
                   @Param("qty") int qty);

    /** 查当前有效累计件数（无计数行返回 null）。 */
    @Select("SELECT * FROM t_seckill_user_buy WHERE activity_id = #{activityId} "
            + "AND user_id = #{userId} AND deleted = 0 LIMIT 1")
    SeckillUserBuy selectActive(@Param("activityId") Long activityId, @Param("userId") Long userId);
}
