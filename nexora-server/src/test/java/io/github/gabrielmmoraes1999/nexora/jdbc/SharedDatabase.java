package io.github.gabrielmmoraes1999.nexora.jdbc;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.Session;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Um arquivo aberto pelo JDBC. Várias conexões no mesmo processo compartilham
 * o banco; cada uma autentica de novo. Outro processo recebe "o banco já está aberto".
 */
final class SharedDatabase {

    private static final Map<Path, SharedDatabase> OPEN = new HashMap<>();

    private final Path file;
    private final Database database;
    private int references;
    private Session transactionOwner;

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

    boolean ownedBy(Session session) {
        return transactionOwner == session;
    }

    void noteTransaction(Session session) {
        if (!database.transactionActive()) {
            transactionOwner = null;
        } else if (transactionOwner == null) {
            transactionOwner = session;
        }
    }

    void release(Session session) {
        synchronized (OPEN) {
            try {
                if (session != null && transactionOwner == session && database.transactionActive()) {
                    database.execute(session, "ROLLBACK");
                }
            } finally {
                if (transactionOwner == session) {
                    transactionOwner = null;
                }
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
