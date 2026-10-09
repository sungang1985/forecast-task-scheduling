package com.forecast.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.forecast.common.exception.ErrorCode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应结构。
 *
 * @param <T> 业务数据类型
 */
@Data
@NoArgsConstructor
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 0 表示成功，其余见 {@link ErrorCode} */
    private int code;
    private String message;
    private T data;
    /** 链路追踪 ID，便于排查问题 */
    private String traceId;
    private long timestamp = System.currentTimeMillis();

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = ErrorCode.SUCCESS.getCode();
        r.message = ErrorCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode.getCode(), message);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }
}
