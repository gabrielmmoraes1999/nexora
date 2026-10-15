package io.github.gabrielmmoraes1999.nexora;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;

final class Values {

    static final Object MISSING = new Object();
    private static final DateTimeFormatter SQL_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    private Values() {
    }

    static Object coerce(DataType type, int length, int scale, Object value) {
        if (value == null || value == MISSING) {
            return value;
        }
        return switch (type) {
            case SMALLINT -> toShort(value);
            case INTEGER -> toInt(value);
            case BIGINT -> toLong(value, "BIGINT");
            case FLOAT -> toFloat(value);
            case DOUBLE -> toDouble(value);
            case BOOLEAN -> toBoolean(value);
            case CHAR, VARCHAR -> toText(value, type, length);
            case DATE -> toDate(value);
            case TIME -> toTime(value);
            case TIMESTAMP -> toTimestamp(value);
            case DECIMAL -> toDecimal(value, length, scale);
            case BLOB -> toBytes(value);
        };
    }

    static long toLong(Object value) {
        return toLong(value, "INTEGER");
    }

    static int toInt(Object value) {
        long number = toLong(value, "INTEGER");
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new NexException("numeric value does not fit in INTEGER: " + display(value));
        }
        return (int) number;
    }

    static short toShort(Object value) {
        long number = toLong(value, "SMALLINT");
        if (number < Short.MIN_VALUE || number > Short.MAX_VALUE) {
            throw new NexException("numeric value does not fit in SMALLINT: " + display(value));
        }
        return (short) number;
    }

    private static long toLong(Object value, String typeName) {
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigDecimal decimal) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException exception) {
                throw new NexException("numeric value does not fit in " + typeName + ": " + display(value));
            }
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number) || number != Math.rint(number)) {
                throw new NexException("fractional value cannot be " + typeName + ": " + display(value));
            }
            if (number > Long.MAX_VALUE || number < Long.MIN_VALUE) {
                throw new NexException("numeric value does not fit in " + typeName + ": " + display(value));
            }
            return (long) number;
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException exception) {
                throw new NexException("cannot convert '" + display(value) + "' to " + typeName);
            }
        }
        throw new NexException("cannot convert " + display(value) + " to " + typeName);
    }

    static float toFloat(Object value) {
        double number;
        if (value instanceof Float || value instanceof Double || value instanceof Long
                || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            number = ((Number) value).doubleValue();
        } else if (value instanceof BigDecimal decimal) {
            number = decimal.doubleValue();
        } else if (value instanceof String text) {
            try {
                number = Double.parseDouble(text.trim());
            } catch (NumberFormatException exception) {
                throw new NexException("cannot convert '" + display(value) + "' to FLOAT");
            }
        } else {
            throw new NexException("cannot convert " + display(value) + " to FLOAT");
        }
        if (!Double.isFinite(number)) {
            throw new NexException("FLOAT does not accept infinity or NaN");
        }
        float narrow = (float) number;
        if (!Float.isFinite(narrow)) {
            throw new NexException("numeric value does not fit in FLOAT: " + display(value));
        }
        return narrow;
    }

    static LocalTime toTime(Object value) {
        if (value instanceof LocalTime time) {
            return time;
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        if (value instanceof String text) {
            try {
                return LocalTime.parse(text.trim());
            } catch (DateTimeParseException exception) {
                throw new NexException("invalid time: " + display(value) + " (use HH:mm[:ss[.SSS]])");
            }
        }
        throw new NexException("cannot convert " + display(value) + " to TIME");
    }

    static double toDouble(Object value) {
        if (value instanceof Double || value instanceof Float || value instanceof Long
                || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new NexException("DOUBLE does not accept infinity or NaN");
            }
            return number;
        }
        if (value instanceof BigDecimal decimal) {
            double number = decimal.doubleValue();
            if (!Double.isFinite(number)) {
                throw new NexException("value does not fit in DOUBLE: " + display(value));
            }
            return number;
        }
        if (value instanceof String text) {
            try {
                double number = Double.parseDouble(text.trim());
                if (!Double.isFinite(number)) {
                    throw new NexException("DOUBLE does not accept infinity or NaN");
                }
                return number;
            } catch (NumberFormatException exception) {
                throw new NexException("cannot convert '" + display(value) + "' to DOUBLE");
            }
        }
        throw new NexException("cannot convert " + display(value) + " to DOUBLE");
    }

    static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            long raw = number.longValue();
            if (raw == 0) {
                return false;
            }
            if (raw == 1) {
                return true;
            }
        }
        if (value instanceof String text) {
            String folded = text.trim().toLowerCase(Locale.ROOT);
            if (folded.equals("true") || folded.equals("1")) {
                return true;
            }
            if (folded.equals("false") || folded.equals("0")) {
                return false;
            }
        }
        throw new NexException("cannot convert " + display(value) + " to BOOLEAN");
    }

    static String toText(Object value, DataType type, int length) {
        String text;
        if (value instanceof String raw) {
            text = raw;
        } else if (value instanceof byte[]) {
            throw new NexException("use a string, not a BLOB, in column " + type);
        } else if (value instanceof BigDecimal decimal) {
            text = decimal.toPlainString();
        } else {
            text = String.valueOf(value);
        }
        if ((type == DataType.CHAR || type == DataType.VARCHAR) && text.length() > length) {
            throw new NexException("text exceeds " + length + " characters");
        }
        return text;
    }

    static LocalDate toDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Number number) {
            return LocalDate.ofEpochDay(toLong(number));
        }
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text.trim());
            } catch (DateTimeParseException exception) {
                throw new NexException("invalid date: " + display(value) + " (use YYYY-MM-DD)");
            }
        }
        throw new NexException("cannot convert " + display(value) + " to DATE");
    }

    static LocalDateTime toTimestamp(Object value) {
        if (value instanceof LocalDateTime timestamp) {
            return timestamp;
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
        }
        if (value instanceof Number number) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(toLong(number)), ZoneOffset.UTC);
        }
        if (value instanceof String text) {
            String raw = text.trim();
            try {
                return LocalDateTime.parse(raw);
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDateTime.parse(raw, SQL_TIMESTAMP);
                } catch (DateTimeParseException exception) {
                    throw new NexException("invalid timestamp: " + display(value));
                }
            }
        }
        throw new NexException("cannot convert " + display(value) + " to TIMESTAMP");
    }

    static BigDecimal toDecimal(Object value, int precision, int scale) {
        BigDecimal decimal;
        if (value instanceof BigDecimal raw) {
            decimal = raw;
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            decimal = BigDecimal.valueOf(((Number) value).longValue());
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new NexException("DECIMAL does not accept infinity or NaN");
            }
            decimal = BigDecimal.valueOf(number);
        } else if (value instanceof String text) {
            try {
                decimal = new BigDecimal(text.trim());
            } catch (NumberFormatException exception) {
                throw new NexException("cannot convert '" + display(value) + "' to DECIMAL");
            }
        } else {
            throw new NexException("cannot convert " + display(value) + " to DECIMAL");
        }
        try {
            decimal = decimal.setScale(scale, java.math.RoundingMode.HALF_UP);
        } catch (ArithmeticException exception) {
            throw new NexException("could not adjust the scale of " + display(value));
        }
        if (decimal.precision() > precision) {
            throw new NexException("value " + decimal.toPlainString() + " exceeds DECIMAL(" + precision + "," + scale + ")");
        }
        return decimal;
    }

    static byte[] toBytes(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof java.nio.ByteBuffer buffer) {
            java.nio.ByteBuffer copy = buffer.asReadOnlyBuffer();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return bytes;
        }
        throw new NexException("BLOB accepts byte[] or X'hex'; received " + display(value));
    }

    static String display(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[] bytes) {
            return "BLOB(" + bytes.length + ")";
        }
        String text = value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
        if (text.length() > 80) {
            return text.substring(0, 80) + "...";
        }
        return text;
    }

    static int compare(Object left, Object right) {
        if (left instanceof byte[] leftBytes && right instanceof byte[] rightBytes) {
            return Arrays.compareUnsigned(leftBytes, rightBytes);
        }
        BigDecimal leftNumber = asDecimal(left);
        BigDecimal rightNumber = asDecimal(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber);
        }
        if (left instanceof LocalDate && right instanceof String text) {
            right = toDate(text);
        } else if (right instanceof LocalDate && left instanceof String text) {
            left = toDate(text);
        }
        if (left instanceof LocalDateTime && right instanceof String text) {
            right = toTimestamp(text);
        } else if (right instanceof LocalDateTime && left instanceof String text) {
            left = toTimestamp(text);
        }
        if (left instanceof LocalTime && right instanceof String text) {
            right = toTime(text);
        } else if (right instanceof LocalTime && left instanceof String text) {
            left = toTime(text);
        }
        if (left.getClass() != right.getClass() && !sameFamily(left, right)) {
            throw new NexException("cannot compare " + typeName(left) + " with " + typeName(right));
        }
        if (left instanceof LocalDate date && right instanceof LocalDate other) {
            return date.compareTo(other);
        }
        if (left instanceof LocalDateTime timestamp && right instanceof LocalDateTime other) {
            return timestamp.compareTo(other);
        }
        if (left instanceof LocalTime time && right instanceof LocalTime other) {
            return time.compareTo(other);
        }
        if (left instanceof Boolean bool && right instanceof Boolean other) {
            return bool.compareTo(other);
        }
        if (left instanceof String text && right instanceof String other) {
            return text.compareTo(other);
        }
        if (left instanceof Comparable<?> comparable && left.getClass().isInstance(right)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> raw = (Comparable<Object>) comparable;
            return raw.compareTo(right);
        }
        throw new NexException("cannot compare " + typeName(left) + " with " + typeName(right));
    }

    static boolean sameFamily(Object left, Object right) {
        return asDecimal(left) != null && asDecimal(right) != null;
    }

    static BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                return null;
            }
            return BigDecimal.valueOf(number);
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                return null;
            }
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    static String typeName(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[]) {
            return "BLOB";
        }
        if (value instanceof Short) {
            return "SMALLINT";
        }
        if (value instanceof Integer) {
            return "INTEGER";
        }
        if (value instanceof Long) {
            return "BIGINT";
        }
        if (value instanceof Float) {
            return "FLOAT";
        }
        if (value instanceof Double) {
            return "DOUBLE";
        }
        if (value instanceof LocalTime) {
            return "TIME";
        }
        if (value instanceof BigDecimal) {
            return "DECIMAL";
        }
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        if (value instanceof LocalDate) {
            return "DATE";
        }
        if (value instanceof LocalDateTime) {
            return "TIMESTAMP";
        }
        if (value instanceof String) {
            return "VARCHAR";
        }
        return value.getClass().getSimpleName();
    }

    static boolean like(String text, String pattern, char escape) {
        return matchLike(text, 0, pattern, 0, escape);
    }

    private static boolean matchLike(String text, int textIndex, String pattern, int patternIndex, char escape) {
        while (patternIndex < pattern.length()) {
            char current = pattern.charAt(patternIndex);
            if (current == escape) {
                if (patternIndex + 1 >= pattern.length()) {
                    throw new NexException("incomplete ESCAPE in the LIKE pattern");
                }
                if (textIndex >= text.length() || text.charAt(textIndex) != pattern.charAt(patternIndex + 1)) {
                    return false;
                }
                textIndex++;
                patternIndex += 2;
                continue;
            }
            if (current == '%') {
                patternIndex++;
                if (patternIndex == pattern.length()) {
                    return true;
                }
                for (int next = textIndex; next <= text.length(); next++) {
                    if (matchLike(text, next, pattern, patternIndex, escape)) {
                        return true;
                    }
                }
                return false;
            }
            if (textIndex >= text.length()) {
                return false;
            }
            if (current != '_' && current != text.charAt(textIndex)) {
                return false;
            }
            textIndex++;
            patternIndex++;
        }
        return textIndex == text.length();
    }
}
