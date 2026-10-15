package io.github.gabrielmmoraes1999.nexora;

import java.util.Locale;

/**
 * Options for creating the file. The page size is fixed, as in Firebird:
 * 4096, 8192, 16384, or 32768. The default is 8192.
 */
public record DatabaseOptions(
        int pageSize,
        String user,
        String password,
        String name,
        String description,
        String charset
) {

    public DatabaseOptions {
        pageSize = Format.checkPageSize(pageSize);
        user = required(user, "DBA user", 63).toUpperCase(Locale.ROOT);
        password = secret(password);
        name = required(name, "database name", 63);
        description = required(description, "description", 256);
        charset = charset(charset);
    }

    public static DatabaseOptions defaults() {
        return new DatabaseOptions(Format.DEFAULT_PAGE_SIZE, "DBA", "dba", "NEXORA", "Nexora", "UTF8");
    }

    private static String required(String value, String kind, int max) {
        if (value == null || value.isBlank()) {
            throw new NexException(kind + " is required");
        }
        String text = value.trim();
        if (text.length() > max) {
            throw new NexException(kind + " exceeds " + max + " characters");
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 32) {
                throw new NexException(kind + " contains an invalid character");
            }
        }
        return text;
    }

    private static String secret(String value) {
        if (value == null || value.isEmpty()) {
            throw new NexException("DBA password is required");
        }
        if (value.length() > 72) {
            throw new NexException("DBA password exceeds 72 characters");
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 32) {
                throw new NexException("DBA password contains an invalid character");
            }
        }
        return value;
    }

    private static String charset(String value) {
        String text = required(value, "character set", 32).toUpperCase(Locale.ROOT);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                throw new NexException("invalid character set: " + text);
            }
        }
        return text;
    }
}
