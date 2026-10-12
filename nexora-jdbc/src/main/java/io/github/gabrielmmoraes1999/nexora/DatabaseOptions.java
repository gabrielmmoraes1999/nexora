package io.github.gabrielmmoraes1999.nexora;

import java.util.Locale;

/**
 * Opções da criação do arquivo. A página fica fixa, como no Firebird:
 * 4096, 8192, 16384 ou 32768. O padrão é 8192.
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
        user = required(user, "usuário DBA", 63).toUpperCase(Locale.ROOT);
        password = secret(password);
        name = required(name, "nome do banco", 63);
        description = required(description, "descrição", 256);
        charset = charset(charset);
    }

    public static DatabaseOptions defaults() {
        return new DatabaseOptions(Format.DEFAULT_PAGE_SIZE, "DBA", "dba", "NEXORA", "Nexora", "UTF8");
    }

    private static String required(String value, String kind, int max) {
        if (value == null || value.isBlank()) {
            throw new NexException(kind + " é obrigatório");
        }
        String text = value.trim();
        if (text.length() > max) {
            throw new NexException(kind + " excede " + max + " caracteres");
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 32) {
                throw new NexException(kind + " contém caractere inválido");
            }
        }
        return text;
    }

    private static String secret(String value) {
        if (value == null || value.isEmpty()) {
            throw new NexException("senha do DBA é obrigatória");
        }
        if (value.length() > 72) {
            throw new NexException("senha do DBA excede 72 caracteres");
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 32) {
                throw new NexException("senha do DBA contém caractere inválido");
            }
        }
        return value;
    }

    private static String charset(String value) {
        String text = required(value, "conjunto de caracteres", 32).toUpperCase(Locale.ROOT);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                throw new NexException("conjunto de caracteres inválido: " + text);
            }
        }
        return text;
    }
}
