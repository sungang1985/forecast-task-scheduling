package com.forecast.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 分页结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private long total;
    private long page;
    private long size;
    private List<T> records = Collections.emptyList();

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(total, page, size, records.stream().map(mapper).toList());
    }
}
