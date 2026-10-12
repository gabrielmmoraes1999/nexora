package io.github.gabrielmmoraes1999.nexora.studio;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Locale;

/**
 * Texto da grade. Célula vazia vira {@code NULL}. Em texto, {@code ''} grava vazio.
 */
public final class ValuesText {

    private ValuesText() {
    }

    public static String show(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[] bytes) {
            return "(BLOB " + bytes.length + ")";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Boolean bool) {
            return bool ? "TRUE" : "FALSE";
        }
        return String.valueOf(value);
    }

    public static String edit(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text.isEmpty() ? "''" : text;
        }
        if (value instanceof byte[] bytes) {
            return "(BLOB " + bytes.length + ")";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Boolean bool) {
            return bool ? "TRUE" : "FALSE";
        }
        return String.valueOf(value);
    }

    public static Object parse(String text, String typeName, boolean nullable) {
        String type = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
        if (text == null || text.isBlank()) {
            if (!nullable) {
                throw new IllegalArgumentException("coluna obrigatória");
            }
            return null;
        }
        String trimmed = text.trim();
        try {
            return switch (type) {
                case "SMALLINT", "INTEGER", "INT", "BIGINT" -> Long.parseLong(trimmed);
                case "FLOAT", "REAL", "DOUBLE", "DOUBLE PRECISION" -> Double.parseDouble(trimmed);
                case "DECIMAL", "NUMERIC" -> new BigDecimal(trimmed);
                case "BOOLEAN", "BOOL" -> bool(trimmed);
                case "DATE" -> Date.valueOf(trimmed);
                case "TIME" -> Time.valueOf(trimmed.length() == 5 ? trimmed + ":00" : trimmed);
                case "TIMESTAMP" -> timestamp(trimmed);
                case "CHAR", "CHARACTER", "VARCHAR", "CHARACTER VARYING" -> string(trimmed);
                case "BLOB", "BYTEA", "BINARY", "VARBINARY" -> throw new IllegalArgumentException("BLOB não se edita na grade");
                default -> trimmed;
            };
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && !exception.getMessage().startsWith("For input")) {
                throw exception;
            }
            throw new IllegalArgumentException("valor inválido para " + type + ": " + trimmed);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("valor inválido para " + type + ": " + trimmed);
        }
    }

    private static Boolean bool(String text) {
        String folded = text.toLowerCase(Locale.ROOT);
        if (folded.equals("true") || folded.equals("1")) {
            return Boolean.TRUE;
        }
        if (folded.equals("false") || folded.equals("0")) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("use TRUE ou FALSE");
    }

    private static String string(String text) {
        if ("''".equals(text)) {
            return "";
        }
        return text;
    }

    private static Timestamp timestamp(String text) {
        String normalized = text.replace('T', ' ');
        if (normalized.length() == 16) {
            normalized = normalized + ":00";
        }
        return Timestamp.valueOf(normalized);
    }
}
