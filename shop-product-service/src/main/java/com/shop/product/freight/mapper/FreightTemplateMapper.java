package com.shop.product.freight.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.product.freight.entity.FreightTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 运费模板 Mapper。is_default 同店唯一走条件更新（CAS），不依赖应用侧加锁。
 */
@Mapper
public interface FreightTemplateMapper extends BaseMapper<FreightTemplate> {

    /**
     * 清除同店其他默认模板（设置某模板为默认前调用）。
     * 条件含 is_default=1，幂等可重入；返回受影响行数。
     */
    @Update("UPDATE t_product_freight_template SET is_default = 0, update_time = NOW() "
            + "WHERE merchant_id = #{merchantId} AND is_default = 1 AND deleted = 0 AND id <> #{templateId}")
    int clearOtherDefault(@Param("merchantId") Long merchantId, @Param("templateId") Long templateId);
}
