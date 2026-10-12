package io.github.gabrielmmoraes1999.nexora.server;

import io.github.gabrielmmoraes1999.nexora.NexException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Configuração do motor: onde escutar e quais arquivos {@code .xdb} publicar.
 *
 * <pre>{@code
 * {
 *   "host": "0.0.0.0",
 *   "port": 4580,
 *   "databases": [
 *     { "name": "loja", "path": "C:/dados/loja.xdb" }
 *   ]
 * }
 * }</pre>
 *
 * {@code host} omitido escuta em todas as interfaces. A porta e ao menos um banco são obrigatórios.
 * O nome na URL {@code jdbc:nexora://host:porta/nome} escolhe a entrada, sem distinguir maiúsculas.
 */
public record MotorConfig(String host, int port, List<DatabaseSpec> databases) {

    public MotorConfig {
        if (host == null || host.isBlank()) {
            throw new NexException("host do motor é obrigatório");
        }
        host = host.trim();
        if (port < 0 || port > 65535) {
            throw new NexException("porta inválida: " + port);
        }
        if (databases == null || databases.isEmpty()) {
            throw new NexException("informe ao menos um banco");
        }
        List<DatabaseSpec> copy = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (DatabaseSpec spec : databases) {
            if (spec == null || spec.name() == null || spec.name().isBlank()) {
                throw new NexException("nome do banco é obrigatório");
            }
            if (spec.path() == null) {
                throw new NexException("caminho do banco é obrigatório");
            }
            String name = spec.name().trim();
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new NexException("nome do banco repetido: " + name);
            }
            copy.add(new DatabaseSpec(name, spec.path().toAbsolutePath().normalize()));
        }
        databases = List.copyOf(copy);
    }

    /**
     * @param name nome usado em {@code jdbc:nexora://host:porta/nome}
     * @param path arquivo {@code .xdb}
     */
    public record DatabaseSpec(String name, Path path) {
    }

    public static MotorConfig read(Path file) {
        if (file == null) {
            throw new NexException("informe o arquivo json do motor");
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException exception) {
            throw new NexException("não foi possível ler o motor: " + exception.getMessage(), exception);
        }
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map<?, ?> object)) {
            throw new NexException("o motor precisa ser um objeto json");
        }
        String host = "0.0.0.0";
        Long port = null;
        List<DatabaseSpec> databases = null;
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new NexException("json inválido");
            }
            switch (key) {
                case "host" -> host = text(entry.getValue(), "host");
                case "port" -> port = integer(entry.getValue(), "porta");
                case "databases" -> databases = databases(entry.getValue(), file);
                default -> throw new NexException("opção desconhecida: " + key);
            }
        }
        if (port == null || port == 0) {
            throw new NexException("porta é obrigatória");
        }
        if (port < 1 || port > 65535) {
            throw new NexException("porta inválida: " + port);
        }
        if (databases == null || databases.isEmpty()) {
            throw new NexException("informe ao menos um banco");
        }
        return new MotorConfig(host, port.intValue(), databases);
    }

    private static List<DatabaseSpec> databases(Object value, Path file) {
        if (!(value instanceof List<?> list)) {
            throw new NexException("databases precisa ser uma lista");
        }
        Path base = file.toAbsolutePath().getParent();
        List<DatabaseSpec> specs = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> object)) {
                throw new NexException("cada banco precisa de nome e caminho");
            }
            String name = null;
            String path = null;
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new NexException("json inválido");
                }
                switch (key) {
                    case "name" -> name = text(entry.getValue(), "nome do banco");
                    case "path" -> path = text(entry.getValue(), "caminho do banco");
                    default -> throw new NexException("opção desconhecida: " + key);
                }
            }
            if (name == null || name.isBlank()) {
                throw new NexException("nome do banco é obrigatório");
            }
            if (path == null || path.isBlank()) {
                throw new NexException("caminho do banco é obrigatório");
            }
            Path resolved = Path.of(path.trim());
            if (!resolved.isAbsolute() && base != null) {
                resolved = base.resolve(resolved);
            }
            specs.add(new DatabaseSpec(name.trim(), resolved));
        }
        return specs;
    }

    private static String text(Object value, String kind) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new NexException(kind + " é obrigatório");
        }
        return text.trim();
    }

    private static Long integer(Object value, String kind) {
        if (!(value instanceof Long number)) {
            throw new NexException(kind + " inválida");
        }
        return number;
    }
}
