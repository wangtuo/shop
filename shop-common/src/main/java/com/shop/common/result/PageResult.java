package com.shop.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页返回结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private long pageNum;
    private long pageSize;
    private long total;
    private List<T> list;

    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(pageNum, pageSize, 0, Collections.emptyList());
    }

    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, List<T> list) {
        return new PageResult<>(pageNum, pageSize, total, list);
    }
}
