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
 * Server configuration: where to listen and which {@code .db} files to publish.
 * The {@code .log} sits beside each file and the server opens it too.
 *
 * <pre>{@code
 * {
 *   "host": "0.0.0.0",
 *   "port": 4580,
 *   "databases": [
 *     { "name": "store", "path": "C:/data/store.db" }
 *   ]
 * }
 * }</pre>
 *
 * An omitted {@code host} listens on all interfaces. The port and at least one database are required.
 * The name in the URL {@code jdbc:nexora://host:port/name} selects the entry, ignoring case.
 */
public record MotorConfig(String host, int port, List<DatabaseSpec> databases) {

    public MotorConfig {
        if (host == null || host.isBlank()) {
            throw new NexException("server host is required");
        }
        host = host.trim();
        if (port < 0 || port > 65535) {
            throw new NexException("invalid port: " + port);
        }
        if (databases == null || databases.isEmpty()) {
            throw new NexException("provide at least one database");
        }
        List<DatabaseSpec> copy = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (DatabaseSpec spec : databases) {
            if (spec == null || spec.name() == null || spec.name().isBlank()) {
                throw new NexException("database name is required");
            }
            if (spec.path() == null) {
                throw new NexException("database path is required");
            }
            String name = spec.name().trim();
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new NexException("duplicate database name: " + name);
            }
            copy.add(new DatabaseSpec(name, spec.path().toAbsolutePath().normalize()));
        }
        databases = List.copyOf(copy);
    }

    /**
     * @param name name used in {@code jdbc:nexora://host:port/name}
     * @param path {@code .db} file
     */
    public record DatabaseSpec(String name, Path path) {
    }

    public static MotorConfig read(Path file) {
        if (file == null) {
            throw new NexException("provide the server json file");
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException exception) {
            throw new NexException("could not read the server: " + exception.getMessage(), exception);
        }
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map<?, ?> object)) {
            throw new NexException("the server file must be a json object");
        }
        String host = "0.0.0.0";
        Long port = null;
        List<DatabaseSpec> databases = null;
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new NexException("invalid json");
            }
            switch (key) {
                case "host" -> host = text(entry.getValue(), "host");
                case "port" -> port = integer(entry.getValue(), "port");
                case "databases" -> databases = databases(entry.getValue(), file);
                default -> throw new NexException("unknown option: " + key);
            }
        }
        if (port == null || port == 0) {
            throw new NexException("port is required");
        }
        if (port < 1 || port > 65535) {
            throw new NexException("invalid port: " + port);
        }
        if (databases == null || databases.isEmpty()) {
            throw new NexException("provide at least one database");
        }
        return new MotorConfig(host, port.intValue(), databases);
    }

    private static List<DatabaseSpec> databases(Object value, Path file) {
        if (!(value instanceof List<?> list)) {
            throw new NexException("databases must be a list");
        }
        Path base = file.toAbsolutePath().getParent();
        List<DatabaseSpec> specs = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> object)) {
                throw new NexException("each database needs a name and a path");
            }
            String name = null;
            String path = null;
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new NexException("invalid json");
                }
                switch (key) {
                    case "name" -> name = text(entry.getValue(), "database name");
                    case "path" -> path = text(entry.getValue(), "database path");
                    default -> throw new NexException("unknown option: " + key);
                }
            }
            if (name == null || name.isBlank()) {
                throw new NexException("database name is required");
            }
            if (path == null || path.isBlank()) {
                throw new NexException("database path is required");
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
            throw new NexException(kind + " is required");
        }
        return text.trim();
    }

    private static Long integer(Object value, String kind) {
        if (!(value instanceof Long number)) {
            throw new NexException("invalid " + kind);
        }
        return number;
    }
}
