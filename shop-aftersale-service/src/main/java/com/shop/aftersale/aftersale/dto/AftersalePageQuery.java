package com.shop.aftersale.aftersale.dto;

import com.shop.common.result.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 售后单分页查询。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AftersalePageQuery extends PageQuery {
    private String orderNo;
    private Integer type;
    private Integer status;
}
