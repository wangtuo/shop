package com.shop.product.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.product.category.entity.ProductSpuCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * SPU-类目多挂载关系 Mapper（B13）。
 */
@Mapper
public interface ProductSpuCategoryMapper extends BaseMapper<ProductSpuCategory> {

    /** 按挂载类目查全部 SPU ID（含实体/虚拟挂载，前台类目列表并入查询用）。 */
    @Select("SELECT spu_id FROM t_product_spu_category WHERE category_id = #{categoryId} AND deleted = 0")
    List<Long> selectSpuIdsByCategory(@Param("categoryId") Long categoryId);

    /** 查 SPU 的全部挂载关系（按挂载类型、类目排序）。 */
    @Select("SELECT * FROM t_product_spu_category WHERE spu_id = #{spuId} AND deleted = 0 "
            + "ORDER BY mount_type, category_id")
    List<ProductSpuCategory> selectBySpu(@Param("spuId") Long spuId);

    /**
     * 卸载单条挂载（deleted 置行 id：uk_spu_category 不含 deleted，同 SPU+类目可再次挂载）。
     * @return 影响行数，0 表示挂载不存在
     */
    @Update("UPDATE t_product_spu_category SET deleted = id "
            + "WHERE spu_id = #{spuId} AND category_id = #{categoryId} AND deleted = 0")
    int deleteOne(@Param("spuId") Long spuId, @Param("categoryId") Long categoryId);

    /** 建品保存时整批替换虚拟挂载：先逻辑删除全部 mount_type=2 旧关系。 */
    @Update("UPDATE t_product_spu_category SET deleted = id "
            + "WHERE spu_id = #{spuId} AND mount_type = 2 AND deleted = 0")
    int deleteVirtualBySpu(@Param("spuId") Long spuId);
}
