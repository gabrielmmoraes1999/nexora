package io.github.gabrielmmoraes1999.seadb.jdbc;

import io.github.gabrielmmoraes1999.seadb.Database;
import io.github.gabrielmmoraes1999.seadb.Session;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A file opened by JDBC. Several connections in the same process share
 * the database; each one authenticates again. Another process receives "the database is already open".
 */
final class SharedDatabase {

    private static final Map<Path, SharedDatabase> OPEN = new HashMap<>();

    private final Path file;
    private final Database database;
    private int references;

    private SharedDatabase(Path file, Database database) {
        this.file = file;
        this.database = database;
        this.references = 1;
    }

    record Opened(SharedDatabase shared, Session session) {
    }

    static Opened open(String path, String user, String password, String role) {
        Path file = Path.of(path).toAbsolutePath().normalize();
        synchronized (OPEN) {
            SharedDatabase existing = OPEN.get(file);
            if (existing != null) {
                Session session = existing.database.login(user, password, role);
                existing.references++;
                return new Opened(existing, session);
            }
            Database database = Database.open(file, user, password, role);
            SharedDatabase created = new SharedDatabase(file, database);
            OPEN.put(file, created);
            return new Opened(created, database.session());
        }
    }

    Database database() {
        return database;
    }

    void release(Session session) {
        synchronized (OPEN) {
            try {
                if (session != null && database.transactionActive(session)) {
                    database.execute(session, "ROLLBACK");
                }
            } finally {
                if (session != null) {
                    database.logout(session);
                }
                references--;
                if (references == 0) {
                    OPEN.remove(file);
                    database.close();
                }
            }
        }
    }
}
