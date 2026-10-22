package io.github.gabrielmmoraes1999.nexora.jdbc;

import io.github.gabrielmmoraes1999.nexora.NexException;
import io.github.gabrielmmoraes1999.nexora.ColumnMeta;
import io.github.gabrielmmoraes1999.nexora.DataType;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.Locale;
import java.util.regex.Pattern;

final class Jdbc {

    private Jdbc() {
    }

    static SQLException wrap(RuntimeException exception) {
        if (exception instanceof NexException) {
            return new SQLException(exception.getMessage(), "42000", exception);
        }
        return new SQLException(exception.getMessage(), "HY000", exception);
    }

    static SQLFeatureNotSupportedException unsupported(String feature) {
        return new SQLFeatureNotSupportedException(feature + " is not supported by Nexora");
    }

    static boolean nameMatches(String pattern, String name) {
        if (pattern == null || pattern.isEmpty() || "%".equals(pattern)) {
            return true;
        }
        StringBuilder regex = new StringBuilder("(?i)^");
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '\\' && i + 1 < pattern.length()) {
                regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (ch == '%') {
                regex.append(".*");
            } else if (ch == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        regex.append('$');
        return name.matches(regex.toString());
    }

    static String firstKeyword(String sql) {
        if (sql == null) {
            return "";
        }
        int index = 0;
        int length = sql.length();
        while (index < length) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '-' && index + 1 < length && sql.charAt(index + 1) == '-') {
                index += 2;
                while (index < length && sql.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < length && sql.charAt(index + 1) == '*') {
                int close = sql.indexOf("*/", index + 2);
                index = close < 0 ? length : close + 2;
                continue;
            }
            break;
        }
        int start = index;
        while (index < length && Character.isLetter(sql.charAt(index))) {
            index++;
        }
        return sql.substring(start, index).toUpperCase(Locale.ROOT);
    }

    static boolean skipsTransaction(String sql) {
        return switch (firstKeyword(sql)) {
            case "BEGIN", "START", "COMMIT", "ROLLBACK" -> true;
            default -> false;
        };
    }

    static boolean readOnlyCommand(String sql) {
        return switch (firstKeyword(sql)) {
            case "SELECT", "WITH", "BEGIN", "START", "COMMIT", "ROLLBACK" -> true;
            default -> false;
        };
    }

    static int sqlType(DataType type) {
        return switch (type) {
            case SMALLINT -> Types.SMALLINT;
            case INTEGER -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case FLOAT -> Types.REAL;
            case DOUBLE -> Types.DOUBLE;
            case BOOLEAN -> Types.BOOLEAN;
            case CHAR -> Types.CHAR;
            case VARCHAR -> Types.VARCHAR;
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIMESTAMP -> Types.TIMESTAMP;
            case DECIMAL -> Types.DECIMAL;
            case BLOB -> Types.BLOB;
        };
    }

    static String className(DataType type) {
        return switch (type) {
            case SMALLINT -> "java.lang.Short";
            case INTEGER -> "java.lang.Integer";
            case BIGINT -> "java.lang.Long";
            case FLOAT -> "java.lang.Float";
            case DOUBLE -> "java.lang.Double";
            case BOOLEAN -> "java.lang.Boolean";
            case CHAR, VARCHAR -> "java.lang.String";
            case DATE -> "java.time.LocalDate";
            case TIME -> "java.time.LocalTime";
            case TIMESTAMP -> "java.time.LocalDateTime";
            case DECIMAL -> "java.math.BigDecimal";
            case BLOB -> "[B";
        };
    }

    static int precision(DataType type, int length) {
        return switch (type) {
            case SMALLINT -> 5;
            case INTEGER -> 10;
            case BIGINT -> 19;
            case FLOAT -> 7;
            case DOUBLE -> 17;
            case BOOLEAN -> 1;
            case CHAR, VARCHAR -> Math.max(length, 0);
            case BLOB -> 8_388_608;
            case DATE -> 10;
            case TIME -> 18;
            case TIMESTAMP -> 26;
            case DECIMAL -> length > 0 ? length : 38;
        };
    }

    static int displaySize(DataType type, int length) {
        return switch (type) {
            case SMALLINT -> 6;
            case INTEGER -> 11;
            case BIGINT -> 20;
            case FLOAT -> 15;
            case DOUBLE -> 24;
            case BOOLEAN -> 5;
            case CHAR, VARCHAR -> length > 0 ? length : 32;
            case DATE -> 10;
            case TIME -> 18;
            case TIMESTAMP -> 26;
            case DECIMAL -> length > 0 ? length + 2 : 20;
            case BLOB -> 32;
        };
    }

    static boolean signed(DataType type) {
        return type == DataType.SMALLINT || type == DataType.INTEGER || type == DataType.BIGINT
                || type == DataType.FLOAT || type == DataType.DOUBLE || type == DataType.DECIMAL;
    }

    static Integer decimalDigits(DataType type, int scale) {
        return switch (type) {
            case SMALLINT, INTEGER, BIGINT, DECIMAL, TIMESTAMP -> scale;
            case TIME -> 9;
            default -> null;
        };
    }

    static Integer radix(DataType type) {
        return switch (type) {
            case SMALLINT, INTEGER, BIGINT, FLOAT, DOUBLE, DECIMAL -> 10;
            default -> null;
        };
    }

    static Integer octetLength(DataType type, int length) {
        return switch (type) {
            case CHAR, VARCHAR -> length > 0 ? length * 4 : null;
            case BLOB -> 8_388_608;
            default -> null;
        };
    }
}

record JdbcColumn(
        String name,
        int sqlType,
        String typeName,
        int precision,
        int scale,
        int nullable,
        boolean signed,
        boolean autoIncrement,
        String className,
        int displaySize,
        String table,
        String schema) {

    static JdbcColumn of(ColumnMeta meta) {
        DataType type = meta.type();
        return new JdbcColumn(
                meta.name(),
                Jdbc.sqlType(type),
                type.name(),
                Jdbc.precision(type, 0),
                0,
                ResultSetMetaData.columnNullableUnknown,
                Jdbc.signed(type),
                false,
                Jdbc.className(type),
                Jdbc.displaySize(type, 0),
                meta.table() == null ? "" : meta.table(),
                meta.schema() == null ? "" : meta.schema());
    }

    static JdbcColumn text(String name) {
        return new JdbcColumn(name, Types.VARCHAR, "VARCHAR", 256, 0,
                ResultSetMetaData.columnNullable, false, false, "java.lang.String", 256, "", "");
    }

    static JdbcColumn integer(String name) {
        return new JdbcColumn(name, Types.INTEGER, "INTEGER", 10, 0,
                ResultSetMetaData.columnNullable, true, false, "java.lang.Integer", 11, "", "");
    }

    static JdbcColumn smallint(String name) {
        return new JdbcColumn(name, Types.SMALLINT, "SMALLINT", 5, 0,
                ResultSetMetaData.columnNullable, true, false, "java.lang.Short", 6, "", "");
    }

    static JdbcColumn bool(String name) {
        return new JdbcColumn(name, Types.BOOLEAN, "BOOLEAN", 1, 0,
                ResultSetMetaData.columnNoNulls, false, false, "java.lang.Boolean", 5, "", "");
    }

    static JdbcColumn bigint(String name) {
        return new JdbcColumn(name, Types.BIGINT, "BIGINT", 19, 0,
                ResultSetMetaData.columnNullable, true, false, "java.lang.Long", 20, "", "");
    }
}
