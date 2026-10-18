package io.github.gabrielmmoraes1999.nexora.backup;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.NexException;
import io.github.gabrielmmoraes1999.nexora.jdbc.NexTarget;
import io.github.gabrielmmoraes1999.nexora.net.BackupClient;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/**
 * Full backup and incremental backup.
 * A file path copies on this machine. A JDBC URL needs a DBA.
 * {@code jdbc:nexora:embedded:<file.db>} opens the file in this process.
 * {@code jdbc:nexora://host:port/name} asks the server that holds the file to send the copy.
 */
public final class BackupService {

    private BackupService() {
    }

    /**
     * Copies the database into {@code directory}.
     * A full backup also resets the live log after the files have been written.
     * An incremental backup copies only the {@code .log} and leaves the live log in place.
     *
     * @param source a {@code .db} path or a {@code jdbc:nexora:} URL
     * @return the same line the CLI prints when the copy has been written
     */
    public static String backup(String source, String user, String password, String role,
                                boolean incremental, Path directory, IntConsumer progress) {
        if (source == null || source.isBlank()) {
            throw new NexException("provide the .db file");
        }
        if (directory == null) {
            throw new NexException("backup directory is required");
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
        return text != null && text.regionMatches(true, 0, "jdbc:nexora:", 0, "jdbc:nexora:".length());
    }

    private static void backupConnection(String url, String user, String password, String role,
                                         boolean incremental, Path directory, IntConsumer progress) {
        requireCredentials(user, password);
        NexTarget target = NexTarget.parse(url);
        if (target.embedded()) {
            if (target.path().isBlank()) {
                throw new NexException("specify the file in the URL jdbc:nexora:<file.db>");
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
                throw new NexException("only the DBA can backup the database");
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
            throw new NexException("user and password are required");
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
            throw new NexException("invalid path: " + text.trim());
        }
    }

    private static void report(IntConsumer progress, int percent) {
        if (progress != null) {
            progress.accept(percent);
        }
    }
}
