package com.shop.aftersale.aftersale.dto;

import com.shop.aftersale.aftersale.entity.AftersaleItem;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 售后单详情。
 */
@Data
public class AftersaleDetailVO implements Serializable {
    private AftersaleOrder aftersale;
    private List<AftersaleItem> items;
}
