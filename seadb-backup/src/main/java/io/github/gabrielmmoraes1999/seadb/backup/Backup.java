package io.github.gabrielmmoraes1999.seadb.backup;

import io.github.gabrielmmoraes1999.seadb.SeaException;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Full backup and incremental backup.
 * With no arguments, the window opens. With a source and a directory, the command writes the copy.
 *
 * <pre>{@code
 * java -jar seadb-backup-1.0.jar
 * java -jar seadb-backup-1.0.jar full store.db C:/data/backup
 * java -jar seadb-backup-1.0.jar incremental store.db C:/data/logs
 * java -jar seadb-backup-1.0.jar full jdbc:seadb://127.0.0.1:4580/store --user dba --password secret C:/data/backup
 * }</pre>
 */
public final class Backup {

    private Backup() {
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0) {
            try {
                execute(args);
            } catch (SeaException exception) {
                System.err.println(exception.getMessage());
                System.exit(1);
            }
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                SwingUtilities.invokeLater(() -> BackupFrame.report(null, error)));
        Look.install();
        SwingUtilities.invokeLater(() -> new BackupFrame().setVisible(true));
    }

    /**
     * Copies the database named by {@code args} into a directory.
     * {@code --help} prints the usage. With no kind, the copy is full.
     * A JDBC URL needs a user and a password, and that user must be a DBA.
     */
    public static void execute(String[] args) {
        if (args != null && args.length > 0 && help(args[0])) {
            System.out.println(usage());
            return;
        }
        Request request = parse(args);
        int[] shown = {-1};
        String written = BackupService.backup(request.source(), request.user(), request.password(), request.role(),
                request.incremental(), request.directory(), percent -> {
                    if (percent == shown[0]) {
                        return;
                    }
                    shown[0] = percent;
                    System.out.print("\rbackup: " + percent + "%");
                    System.out.flush();
                });
        if (shown[0] >= 0) {
            System.out.println();
        }
        System.out.println(written);
    }

    static String usage() {
        return """
                Usage:
                  java -jar seadb-backup-1.0.jar
                  java -jar seadb-backup-1.0.jar full <file.db> <directory>
                  java -jar seadb-backup-1.0.jar incremental <file.db> <directory>
                  java -jar seadb-backup-1.0.jar <file.db> <directory>
                  java -jar seadb-backup-1.0.jar full <jdbc-url> --user <user> --password <password> <directory>

                With no arguments, the window opens.
                <file.db> may be a jdbc:seadb: URL. A URL needs --user and --password. --role is optional.
                full copies the .db and the .log and resets the live log.
                incremental copies only the .log and leaves the live log in place.
                With no kind, the copy is full.
                The command shows the percentage while it copies.
                jdbc:seadb:embedded:<file.db> opens the file here.
                jdbc:seadb://host:port/name asks the server for the copy.
                The directory is on this machine.
                While that copy runs, other sessions on the server wait.
                The live log resets after a full backup has been written.

                Example:
                  java -jar seadb-backup-1.0.jar full C:/data/store.db C:/data/backup
                  java -jar seadb-backup-1.0.jar incremental C:/data/store.db C:/data/logs
                  java -jar seadb-backup-1.0.jar full jdbc:seadb://127.0.0.1:4580/store --user dba --password secret C:/data/backup
                """;
    }

    private static Request parse(String[] args) {
        if (args == null || args.length == 0) {
            throw new SeaException(usage());
        }
        String kind = "full";
        boolean kindSet = false;
        String url = null;
        String file = null;
        String directory = null;
        String user = null;
        String password = null;
        String role = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-")) {
                String key = arg;
                String value = null;
                int equals = arg.indexOf('=');
                if (equals >= 0) {
                    key = arg.substring(0, equals);
                    value = arg.substring(equals + 1);
                }
                if (value == null) {
                    if (i + 1 >= args.length) {
                        throw new SeaException("missing value for " + key);
                    }
                    value = args[++i];
                }
                switch (normal(key)) {
                    case "url" -> {
                        if (url != null) {
                            throw new SeaException("connection specified more than once");
                        }
                        url = value;
                    }
                    case "user" -> user = value;
                    case "password" -> password = value;
                    case "role" -> role = value;
                    default -> throw new SeaException("unknown option: " + key);
                }
                continue;
            }
            if (!kindSet && file == null && url == null && directory == null && backupKind(arg) != null) {
                kind = backupKind(arg);
                kindSet = true;
                continue;
            }
            if (file == null && url == null) {
                if (BackupService.connection(arg)) {
                    url = arg;
                } else {
                    file = arg;
                }
                continue;
            }
            if (directory == null) {
                directory = arg;
                continue;
            }
            throw new SeaException("too many arguments\n\n" + usage());
        }
        if (directory == null || directory.isBlank() || (file != null && url != null)) {
            throw new SeaException(usage());
        }
        if (file != null && file.isBlank()) {
            throw new SeaException(usage());
        }
        if (url == null && file == null) {
            throw new SeaException(usage());
        }
        return new Request(url != null ? url : file, user, password, role, kind.equals("incremental"), Path.of(directory));
    }

    private static String backupKind(String text) {
        String kind = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "full", "incremental" -> kind;
            default -> null;
        };
    }

    private static boolean help(String arg) {
        return arg.equalsIgnoreCase("--help") || arg.equalsIgnoreCase("-h");
    }

    private static String normal(String key) {
        String text = key;
        while (text.startsWith("-")) {
            text = text.substring(1);
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private record Request(String source, String user, String password, String role,
                           boolean incremental, Path directory) {
    }
}
