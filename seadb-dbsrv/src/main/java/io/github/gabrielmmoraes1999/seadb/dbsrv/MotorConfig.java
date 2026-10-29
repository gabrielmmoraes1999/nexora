package io.github.gabrielmmoraes1999.seadb.dbsrv;

import io.github.gabrielmmoraes1999.seadb.SeaException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where the server listens and which {@code .db} files it publishes.
 * The {@code .log} sits beside each file and the server opens it too.
 * The name in {@code jdbc:seadb://host:port/name} selects the entry, ignoring case.
 */
public record MotorConfig(String host, int port, List<DatabaseSpec> databases) {

    public MotorConfig {
        if (host == null || host.isBlank()) {
            throw new SeaException("server host is required");
        }
        host = host.trim();
        if (port < 0 || port > 65535) {
            throw new SeaException("invalid port: " + port);
        }
        if (databases == null || databases.isEmpty()) {
            throw new SeaException("provide at least one database");
        }
        List<DatabaseSpec> copy = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (DatabaseSpec spec : databases) {
            if (spec == null || spec.name() == null || spec.name().isBlank()) {
                throw new SeaException("database name is required");
            }
            if (spec.path() == null) {
                throw new SeaException("database path is required");
            }
            String name = spec.name().trim();
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new SeaException("duplicate database name: " + name);
            }
            copy.add(new DatabaseSpec(name, spec.path().toAbsolutePath().normalize()));
        }
        databases = List.copyOf(copy);
    }

    /**
     * @param name name used in {@code jdbc:seadb://host:port/name}
     * @param path {@code .db} file
     */
    public record DatabaseSpec(String name, Path path) {
    }
}
