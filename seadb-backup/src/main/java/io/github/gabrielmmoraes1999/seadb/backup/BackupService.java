package io.github.gabrielmmoraes1999.seadb.backup;

import io.github.gabrielmmoraes1999.seadb.Database;
import io.github.gabrielmmoraes1999.seadb.SeaException;
import io.github.gabrielmmoraes1999.seadb.jdbc.SeaTarget;
import io.github.gabrielmmoraes1999.seadb.net.BackupClient;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/**
 * Full backup and incremental backup.
 * A file path copies on this machine. A JDBC URL needs a DBA.
 * {@code jdbc:seadb:embedded:<file.db>} opens the file in this process.
 * {@code jdbc:seadb://host:port/name} asks the server that holds the file to send the copy.
 */
public final class BackupService {

    private BackupService() {
    }

    /**
     * Copies the database into {@code directory}.
     * A full backup resets the live log only if concurrent changes or open
     * transactions do not require retaining it.
     * An incremental backup copies only the {@code .log} and leaves the live log in place.
     *
     * @param source a {@code .db} path or a {@code jdbc:seadb:} URL
     * @return the same line the CLI prints when the copy has been written
     */
    public static String backup(String source, String user, String password, String role,
                                boolean incremental, Path directory, IntConsumer progress) {
        if (source == null || source.isBlank()) {
            throw new SeaException("provide the .db file");
        }
        if (directory == null) {
            throw new SeaException("backup directory is required");
        }
        if (connection(source)) {
            backupConnection(source.trim(), user, password, role, incremental, directory, progress);
        } else if (user != null || password != null || role != null) {
            copyAuthenticated(path(source), user, password, role, incremental, directory, progress);
        } else {
            copyFile(path(source), incremental, directory, progress);
        }
        return (incremental ? "incremental backup" : "full backup")
                + " written to: " + directory.toAbsolutePath().normalize();
    }

    public static boolean connection(String text) {
        return text != null && text.regionMatches(true, 0, "jdbc:seadb:", 0, "jdbc:seadb:".length());
    }

    private static void backupConnection(String url, String user, String password, String role,
                                         boolean incremental, Path directory, IntConsumer progress) {
        requireCredentials(user, password);
        SeaTarget target = SeaTarget.parse(url);
        if (target.embedded()) {
            if (target.path().isBlank()) {
                throw new SeaException("specify the file in the URL jdbc:seadb:<file.db>");
            }
            copyAuthenticated(Path.of(target.path()), user, password, blankRole(role), incremental, directory, progress);
            return;
        }
        BackupClient.copy(target.host(), target.port(), target.database(), user, password, blankRole(role),
                incremental, directory, progress);
    }

    private static void copyAuthenticated(Path database, String user, String password, String role,
                                          boolean incremental, Path directory, IntConsumer progress) {
        requireCredentials(user, password);
        try (Database db = Database.open(database, user, password, blankRole(role))) {
            if (!db.session().admin()) {
                throw new SeaException("only the DBA can backup the database");
            }
        }
        copyFile(database, incremental, directory, progress);
    }

    private static void copyFile(Path database, boolean incremental, Path directory, IntConsumer progress) {
        report(progress, 0);
        if (incremental) {
            Database.backupLog(database, directory);
        } else {
            Database.backup(database, directory);
        }
        report(progress, 100);
    }

    private static void requireCredentials(String user, String password) {
        if (user == null || user.isBlank() || password == null || password.isEmpty()) {
            throw new SeaException("user and password are required");
        }
    }

    private static String blankRole(String role) {
        if (role == null || role.isBlank() || role.equalsIgnoreCase("NONE")) {
            return null;
        }
        return role;
    }

    private static Path path(String text) {
        try {
            return Path.of(text.trim());
        } catch (InvalidPathException exception) {
            throw new SeaException("invalid path: " + text.trim());
        }
    }

    private static void report(IntConsumer progress, int percent) {
        if (progress != null) {
            progress.accept(percent);
        }
    }
}
