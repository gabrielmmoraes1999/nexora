package io.github.gabrielmmoraes1999.nexora;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * A materialized row. The column index starts at zero.
 * SQL NULL values are returned as {@code null}.
 */
public final class Row {

    private final List<ColumnMeta> columns;
    private final Object[] values;

    Row(List<ColumnMeta> columns, Object[] values) {
        this.columns = columns;
        this.values = values;
    }

    /**
     * Rebuilds a row received over the network. The two lists must be the same size.
     */
    public static Row of(List<ColumnMeta> columns, Object[] values) {
        if (columns == null || values == null || columns.size() != values.length) {
            throw new NexException("row columns and values differ");
        }
        return new Row(List.copyOf(columns), values.clone());
    }

    public int size() {
        return values.length;
    }

    public List<ColumnMeta> columns() {
        return columns;
    }

    public String columnName(int index) {
        return columns.get(check(index)).name();
    }

    public DataType columnType(int index) {
        return columns.get(check(index)).type();
    }

    public boolean isNull(int index) {
        return values[check(index)] == null;
    }

    public boolean isNull(String name) {
        return isNull(indexOf(name));
    }

    public Object get(int index) {
        return values[check(index)];
    }

    public Object get(String name) {
        return get(indexOf(name));
    }

    public String getString(String name) {
        return getString(indexOf(name));
    }

    public String getString(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            throw new NexException("column " + columnName(index) + " is BLOB; use getBytes");
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }

    public Long getLong(String name) {
        return getLong(indexOf(name));
    }

    public Long getLong(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && !(value instanceof Double) && !(value instanceof Float)) {
            return number.longValue();
        }
        throw mismatch(index, "INTEGER");
    }

    public Integer getInt(String name) {
        Long value = getLong(name);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new NexException("value is outside the int range in column " + name);
        }
        return value.intValue();
    }

    public Short getShort(String name) {
        return getShort(indexOf(name));
    }

    public Short getShort(int index) {
        Long value = getLong(index);
        if (value == null) {
            return null;
        }
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new NexException("value is outside the SMALLINT range in column " + columnName(index));
        }
        return value.shortValue();
    }

    public Float getFloat(String name) {
        return getFloat(indexOf(name));
    }

    public Float getFloat(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof Float || value instanceof Double) {
            float number = ((Number) value).floatValue();
            if (!Float.isFinite(number)) {
                throw new NexException("FLOAT does not accept infinity or NaN");
            }
            return number;
        }
        throw mismatch(index, "FLOAT");
    }

    public LocalTime getTime(String name) {
        return getTime(indexOf(name));
    }

    public LocalTime getTime(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalTime time) {
            return time;
        }
        throw mismatch(index, "TIME");
    }

    public Double getDouble(String name) {
        return getDouble(indexOf(name));
    }

    public Double getDouble(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw mismatch(index, "DOUBLE");
    }

    public Boolean getBoolean(String name) {
        return getBoolean(indexOf(name));
    }

    public Boolean getBoolean(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw mismatch(index, "BOOLEAN");
    }

    public BigDecimal getDecimal(String name) {
        return getDecimal(indexOf(name));
    }

    public BigDecimal getDecimal(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.longValue());
        }
        throw mismatch(index, "DECIMAL");
    }

    public LocalDate getDate(String name) {
        return getDate(indexOf(name));
    }

    public LocalDate getDate(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        throw mismatch(index, "DATE");
    }

    public LocalDateTime getTimestamp(String name) {
        return getTimestamp(indexOf(name));
    }

    public LocalDateTime getTimestamp(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime timestamp) {
            return timestamp;
        }
        throw mismatch(index, "TIMESTAMP");
    }

    public byte[] getBytes(String name) {
        return getBytes(indexOf(name));
    }

    public byte[] getBytes(int index) {
        Object value = get(index);
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof String text) {
            return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        throw mismatch(index, "BLOB");
    }

    private int indexOf(String name) {
        if (name == null) {
            throw new NexException("column name is required");
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return i;
            }
        }
        throw new NexException("column not found: " + name);
    }

    private int check(int index) {
        if (index < 0 || index >= values.length) {
            throw new NexException("column index is out of range: " + index);
        }
        return index;
    }

    private NexException mismatch(int index, String expected) {
        return new NexException("column " + columnName(index) + " is not " + expected);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(columns.get(i).name()).append('=').append(Values.display(values[i]));
        }
        return builder.append('}').toString();
    }
}
