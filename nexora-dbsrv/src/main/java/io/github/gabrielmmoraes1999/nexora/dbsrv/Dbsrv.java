package io.github.gabrielmmoraes1999.nexora.dbsrv;

import io.github.gabrielmmoraes1999.nexora.dbsrv.console.Kind;
import io.github.gabrielmmoraes1999.nexora.dbsrv.console.Launcher;

/**
 * Network database server.
 * The window holds the {@code .db} files and listens on the network, with the message console.
 *
 * <pre>{@code
 * java -jar nexora-dbsrv-1.0.jar -x tcpip(port=4580) C:/data/store.db C:/data/other.db
 * }</pre>
 *
 * Clients use {@code jdbc:nexora://host:4580/store}.
 */
public final class Dbsrv {

    private Dbsrv() {
    }

    public static void main(String[] args) {
        Launcher.start(Kind.NETWORK, args);
    }
}
