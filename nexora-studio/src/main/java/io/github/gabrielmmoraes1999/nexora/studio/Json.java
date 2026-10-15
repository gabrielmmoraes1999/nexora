package io.github.gabrielmmoraes1999.nexora.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON sufficient for the registry: object, list, text, number, boolean, and null.
 */
final class Json {

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("empty registry");
        }
        Json parser = new Json(text);
        parser.skip();
        Object value = parser.value();
        parser.skip();
        if (parser.index != text.length()) {
            throw new IllegalArgumentException("invalid registry at position " + (parser.index + 1));
        }
        return value;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        out.append('\n');
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int indent) {
        switch (value) {
            case null -> out.append("null");
            case String text -> out.append('"').append(escape(text)).append('"');
            case Boolean bool -> out.append(bool);
            case Integer number -> out.append(number);
            case Long number -> out.append(number);
            case Map<?, ?> map -> {
                out.append("{\n");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(",\n");
                    }
                    first = false;
                    pad(out, indent + 1);
                    out.append('"').append(escape(String.valueOf(entry.getKey()))).append("\": ");
                    write(entry.getValue(), out, indent + 1);
                }
                out.append('\n');
                pad(out, indent);
                out.append('}');
            }
            case List<?> list -> {
                if (list.isEmpty()) {
                    out.append("[]");
                    return;
                }
                out.append("[\n");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(",\n");
                    }
                    pad(out, indent + 1);
                    write(list.get(i), out, indent + 1);
                }
                out.append('\n');
                pad(out, indent);
                out.append(']');
            }
            default -> throw new IllegalArgumentException("unsupported registry value: " + value.getClass().getSimpleName());
        }
    }

    private static void pad(StringBuilder out, int indent) {
        out.append("  ".repeat(indent));
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private Object value() {
        skip();
        if (index >= text.length()) {
            throw error("value expected");
        }
        char c = text.charAt(index);
        if (c == '{') {
            return object();
        }
        if (c == '[') {
            return array();
        }
        if (c == '"') {
            return string();
        }
        if (c == 't' || c == 'f') {
            return bool();
        }
        if (c == 'n') {
            return nil();
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return number();
        }
        throw error("invalid value");
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skip();
        if (peek('}')) {
            index++;
            return map;
        }
        while (true) {
            skip();
            String key = string();
            skip();
            expect(':');
            map.put(key, value());
            skip();
            if (peek('}')) {
                index++;
                return map;
            }
            expect(',');
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skip();
        if (peek(']')) {
            index++;
            return list;
        }
        while (true) {
            list.add(value());
            skip();
            if (peek(']')) {
                index++;
                return list;
            }
            expect(',');
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (index >= text.length()) {
                    throw error("unterminated text");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw error("incomplete unicode escape");
                        }
                        int code = Integer.parseInt(text.substring(index, index + 4), 16);
                        out.append((char) code);
                        index += 4;
                    }
                    default -> throw error("invalid escape");
                }
                continue;
            }
            if (c < 32) {
                throw error("control character in text");
            }
            out.append(c);
        }
        throw error("unterminated text");
    }

    private Boolean bool() {
        if (text.startsWith("true", index)) {
            index += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", index)) {
            index += 5;
            return Boolean.FALSE;
        }
        throw error("invalid boolean");
    }

    private Object nil() {
        if (text.startsWith("null", index)) {
            index += 4;
            return null;
        }
        throw error("invalid null");
    }

    private Long number() {
        int start = index;
        if (peek('-')) {
            index++;
        }
        if (index >= text.length() || text.charAt(index) < '0' || text.charAt(index) > '9') {
            throw error("invalid number");
        }
        while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
            index++;
        }
        try {
            return Long.parseLong(text.substring(start, index));
        } catch (NumberFormatException exception) {
            throw error("invalid number");
        }
    }

    private void skip() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }

    private boolean peek(char c) {
        return index < text.length() && text.charAt(index) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        index++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " (position " + (index + 1) + ")");
    }
}
