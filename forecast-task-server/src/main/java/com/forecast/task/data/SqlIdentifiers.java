package com.forecast.task.data;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;

import java.util.regex.Pattern;

/**
 * SQL 标识符校验。数据源配置中的表名/列名会拼接进 SQL，必须严格校验防止注入；
 * 所有取值条件一律使用参数绑定。
 */
public final class SqlIdentifiers {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    /** 允许 schema.table 形式 */
    private static final Pattern TABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}(\\.[A-Za-z_][A-Za-z0-9_]{0,63})?$");

    private SqlIdentifiers() {
    }

    public static String column(String name, String what) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new BizException(ErrorCode.DATA_SOURCE_INVALID, what + " 非法: " + name);
        }
        return name;
    }

    public static String table(String name) {
        if (name == null || !TABLE.matcher(name).matches()) {
            throw new BizException(ErrorCode.DATA_SOURCE_INVALID, "表名非法: " + name);
        }
        return name;
    }

    public static boolean isValidColumn(String name) {
        return name != null && IDENTIFIER.matcher(name).matches();
    }
}
