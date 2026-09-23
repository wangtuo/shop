package com.shop.product.freight.dto;

import com.shop.product.freight.entity.FreightRegion;
import com.shop.product.freight.entity.FreightTemplate;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 运费模板详情（含区域规则）。
 */
@Data
public class FreightTemplateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模板主体 */
    private FreightTemplate template;

    /** 区域规则列表 */
    private List<FreightRegion> regions = new ArrayList<>();
}
