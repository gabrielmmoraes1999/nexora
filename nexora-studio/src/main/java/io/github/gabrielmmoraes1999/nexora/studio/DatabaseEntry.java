package io.github.gabrielmmoraes1999.nexora.studio;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Database registered in the studio. The password stays in memory for this run.
 * The file {@code studio.json} receives it only when {@code rememberPassword} is true.
 */
public record DatabaseEntry(
        String id,
        String alias,
        boolean embedded,
        String path,
        String host,
        int port,
        String database,
        String user,
        String password,
        String role,
        boolean rememberPassword
) {

    public DatabaseEntry {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        alias = alias == null ? "" : alias.trim();
        path = path == null ? "" : path.trim();
        host = host == null || host.isBlank() ? "localhost" : host.trim();
        if (port < 1 || port > 65535) {
            port = 4580;
        }
        database = database == null ? "" : database.trim();
        user = user == null ? "" : user.trim();
        password = password == null ? "" : password;
        role = role == null ? "" : role.trim();
    }

    public String label() {
        if (!alias.isBlank()) {
            return alias;
        }
        if (embedded) {
            try {
                Path file = Path.of(path).getFileName();
                if (file != null && !file.toString().isBlank()) {
                    return file.toString();
                }
            } catch (RuntimeException ignored) {
                return path;
            }
            return path.isBlank() ? "database" : path;
        }
        return database.isBlank() ? host : database;
    }

    public String url() {
        if (embedded) {
            return "jdbc:nexora:embedded:" + path.replace('\\', '/');
        }
        return "jdbc:nexora://" + host + ":" + port + "/" + database;
    }

    public String target() {
        if (embedded) {
            return path.replace('\\', '/');
        }
        return host + ":" + port + "/" + database;
    }

    public DatabaseEntry forStorage() {
        if (rememberPassword) {
            return this;
        }
        return new DatabaseEntry(id, alias, embedded, path, host, port, database, user, "", role, false);
    }
}
