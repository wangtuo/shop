package com.shop.marketing.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.marketing.activity.entity.SeckillSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillSkuMapper extends BaseMapper<SeckillSku> {

    /** 锁定库存：可售 = 总库存 - 锁定 - 已售，条件更新防超卖。 */
    @Update("UPDATE t_seckill_sku SET locked_stock = locked_stock + #{qty}, version = version + 1 "
            + "WHERE id = #{id} AND total_stock - locked_stock - sold_stock >= #{qty} AND deleted = 0")
    int lockStock(@Param("id") Long id, @Param("qty") int qty);

    /** 支付成功：锁定转已售。 */
    @Update("UPDATE t_seckill_sku SET locked_stock = locked_stock - #{qty}, sold_stock = sold_stock + #{qty}, "
            + "version = version + 1 WHERE id = #{id} AND locked_stock >= #{qty} AND deleted = 0")
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    /** 取消释放：锁定回可售。 */
    @Update("UPDATE t_seckill_sku SET locked_stock = locked_stock - #{qty}, version = version + 1 "
            + "WHERE id = #{id} AND locked_stock >= #{qty} AND deleted = 0")
    int releaseStock(@Param("id") Long id, @Param("qty") int qty);
}
