package com.shop.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 分页查询基类，页码从 1 开始。
 */
@Data
public class PageQuery implements Serializable {

    private Integer pageNum = 1;
    private Integer pageSize = 20;

    public long offset() {
        int p = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int s = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 200);
        return (long) (p - 1) * s;
    }

    public int safePageSize() {
        return pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 200);
    }

    public int safePageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }
}
