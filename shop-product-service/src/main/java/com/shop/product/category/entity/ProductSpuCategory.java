package com.shop.product.category.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * SPU-类目多挂载关系（V4 t_product_spu_category）。
 *
 * <p>主归属仍由 {@code t_product_spu.category3_id} 表达；本表承载虚拟类目多二级挂载
 * （mount_type=2）。uk(spu_id, category_id) 防重复挂载。本表无 update_time，
 * 故不继承 {@link com.shop.common.model.BaseEntity}。</p>
 */
@Data
@TableName("t_product_spu_category")
public class ProductSpuCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** SPU ID */
    private Long spuId;

    /** 挂载类目 ID（通常为二级虚拟类目，一级也允许） */
    private Long categoryId;

    /** 挂载类目层级冗余：1/2/3 */
    private Integer categoryLevel;

    /** 挂载类型：1 实体归属 2 虚拟挂载 */
    private Integer mountType;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 逻辑删除 */
    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
