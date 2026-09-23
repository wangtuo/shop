package com.shop.product.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.product.category.entity.ProductAttrKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 规格/属性主数据 Mapper（B13）。
 */
@Mapper
public interface ProductAttrKeyMapper extends BaseMapper<ProductAttrKey> {

    /**
     * 查类目适用的启用属性：类目私有（category_id=#{categoryId}）+ 全局通用（0）。
     * 建品 attrsJson 校验与属性模板下发共用。
     */
    @Select("SELECT * FROM t_product_attr_key WHERE status = 1 AND deleted = 0 "
            + "AND category_id IN (0, #{categoryId}) ORDER BY category_id DESC, sort ASC, id ASC")
    List<ProductAttrKey> selectEnabledByCategory(@Param("categoryId") Long categoryId);
}
