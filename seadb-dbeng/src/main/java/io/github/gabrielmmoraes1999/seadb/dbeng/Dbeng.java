package io.github.gabrielmmoraes1999.seadb.dbeng;

import io.github.gabrielmmoraes1999.seadb.dbsrv.console.Kind;
import io.github.gabrielmmoraes1999.seadb.dbsrv.console.Launcher;

/**
 * Personal database server.
 * The window holds the {@code .db} and listens on this machine, with the message console.
 *
 * <pre>{@code
 * java -jar seadb-dbeng-1.0.jar -p 4580 C:/data/store.db
 * }</pre>
 *
 * Clients on this machine use {@code jdbc:seadb://127.0.0.1:4580/store}.
 */
public final class Dbeng {

    private Dbeng() {
    }

    public static void main(String[] args) {
        Launcher.start(Kind.PERSONAL, args);
    }
}
