package io.github.gabrielmmoraes1999.nexora.server;

import java.nio.file.Path;

/**
 * Events from a running server, for a console or a log file.
 * The methods run on the connection threads. The quiet log ignores them.
 */
public class MotorLog {

    public static final MotorLog QUIET = new MotorLog();

    public void databaseStarted(String name, Path file, int pageSize) {
    }

    public void connected(long id, String database, String user, String address) {
    }

    public void disconnected(long id, String database, String user, String address) {
    }

    public void statement(String database, String user, String sql) {
    }

    public void failed(String message) {
    }
}
