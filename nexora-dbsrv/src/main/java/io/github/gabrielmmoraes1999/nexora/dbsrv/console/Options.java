package io.github.gabrielmmoraes1999.nexora.dbsrv.console;

import io.github.gabrielmmoraes1999.nexora.NexException;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the options typed on the startup window. Quotes keep a value together,
 * as in {@code -x "tcpip(port=4580)"}.
 */
public final class Options {

    private Options() {
    }

    public static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == quote) {
                    quoted = false;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (quoted) {
            throw new NexException("options have an open quote");
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return List.copyOf(parts);
    }
}
