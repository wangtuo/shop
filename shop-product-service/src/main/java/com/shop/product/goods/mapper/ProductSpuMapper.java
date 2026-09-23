package com.shop.product.goods.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.product.goods.entity.ProductSpu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * SPU Mapper：除 BaseMapper 外提供状态机条件更新与评价冗余字段条件更新。
 */
@Mapper
public interface ProductSpuMapper extends BaseMapper<ProductSpu> {

    /**
     * 状态机条件更新：仅当当前状态等于 fromStatus 时才推进到 toStatus，影响 0 行即并发冲突。
     */
    @Update("UPDATE t_product_spu SET status = #{toStatus}, version = version + 1, update_time = NOW() "
            + "WHERE id = #{spuId} AND deleted = 0 AND status = #{fromStatus}")
    int updateStatusIf(@Param("spuId") Long spuId,
                       @Param("fromStatus") int fromStatus,
                       @Param("toStatus") int toStatus);

    /**
     * 评价计数冗余更新：好评数按 goodFlag 条件累加，好评率在同一语句内重算。
     */
    @Update("UPDATE t_product_spu SET good_comment_count = good_comment_count + #{goodFlag}, "
            + "total_comment_count = total_comment_count + 1, "
            + "good_rate = (good_comment_count + #{goodFlag}) / (total_comment_count + 1), "
            + "version = version + 1, update_time = NOW() "
            + "WHERE id = #{spuId} AND deleted = 0")
    int increaseCommentCount(@Param("spuId") Long spuId, @Param("goodFlag") int goodFlag);
}
