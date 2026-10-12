package io.github.gabrielmmoraes1999.nexora.server;

import io.github.gabrielmmoraes1999.nexora.NexException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lê só o objeto, a lista, o texto e o número inteiro que o motor precisa.
 */
final class Json {

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text == null ? "" : text;
        if (this.text.startsWith("\uFEFF")) {
            index = 1;
        }
    }

    static Object parse(String text) {
        Json json = new Json(text);
        json.skip();
        if (json.index >= json.text.length()) {
            throw new NexException("json inválido");
        }
        Object value = json.value();
        json.skip();
        if (json.index < json.text.length()) {
            throw new NexException("json inválido");
        }
        return value;
    }

    private Object value() {
        skip();
        if (index >= text.length()) {
            throw new NexException("json inválido");
        }
        char current = text.charAt(index);
        return switch (current) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skip();
        if (peek('}')) {
            index++;
            return map;
        }
        while (index < text.length()) {
            skip();
            if (text.charAt(index) != '"') {
                throw new NexException("json inválido");
            }
            String key = string();
            if (map.containsKey(key)) {
                throw new NexException("json inválido");
            }
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
        throw new NexException("json inválido");
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skip();
        if (peek(']')) {
            index++;
            return list;
        }
        while (index < text.length()) {
            list.add(value());
            skip();
            if (peek(']')) {
                index++;
                return list;
            }
            expect(',');
        }
        throw new NexException("json inválido");
    }

    private String string() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (index < text.length()) {
            char current = text.charAt(index++);
            if (current == '"') {
                return builder.toString();
            }
            if (current == '\\') {
                if (index >= text.length()) {
                    throw new NexException("json inválido");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> builder.append(escaped);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> builder.append(unicode());
                    default -> throw new NexException("json inválido");
                }
                continue;
            }
            if (current < 0x20) {
                throw new NexException("json inválido");
            }
            builder.append(current);
        }
        throw new NexException("json inválido");
    }

    private char unicode() {
        if (index + 4 > text.length()) {
            throw new NexException("json inválido");
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            char digit = text.charAt(index++);
            int value = Character.digit(digit, 16);
            if (value < 0) {
                throw new NexException("json inválido");
            }
            code = (code << 4) + value;
        }
        return (char) code;
    }

    private Object literal(String expected, Object value) {
        if (!text.startsWith(expected, index)) {
            throw new NexException("json inválido");
        }
        index += expected.length();
        return value;
    }

    private Long number() {
        int start = index;
        if (peek('-')) {
            index++;
        }
        if (index >= text.length() || !digit(text.charAt(index))) {
            throw new NexException("json inválido");
        }
        if (text.charAt(index) == '0') {
            index++;
        } else {
            while (index < text.length() && digit(text.charAt(index))) {
                index++;
            }
        }
        if (index < text.length()) {
            char next = text.charAt(index);
            if (next == '.' || next == 'e' || next == 'E') {
                throw new NexException("json inválido");
            }
        }
        try {
            return Long.parseLong(text.substring(start, index));
        } catch (NumberFormatException exception) {
            throw new NexException("json inválido");
        }
    }

    private void skip() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }

    private void expect(char expected) {
        skip();
        if (index >= text.length() || text.charAt(index) != expected) {
            throw new NexException("json inválido");
        }
        index++;
    }

    private boolean peek(char expected) {
        return index < text.length() && text.charAt(index) == expected;
    }

    private static boolean digit(char current) {
        return current >= '0' && current <= '9';
    }
}
