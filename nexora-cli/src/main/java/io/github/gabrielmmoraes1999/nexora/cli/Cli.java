package io.github.gabrielmmoraes1999.nexora.cli;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.DatabaseOptions;
import io.github.gabrielmmoraes1999.nexora.NexException;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Nexora command line. Creates the {@code .db}, backs up the pair, and applies a log.
 * JDBC and the server open a file that already exists.
 *
 * <pre>{@code
 * java -jar nexora-cli-1.0.jar create store.db --page-size 8192 --user DBA --password secret
 * java -jar nexora-cli-1.0.jar backup full store.db C:/data/backup
 * java -jar nexora-cli-1.0.jar backup incremental store.db C:/data/logs
 * java -jar nexora-cli-1.0.jar apply C:/data/backup/store.db store.log
 * java -jar nexora-cli-1.0.jar export store.log C:/data/store.sql
 * }</pre>
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) {
        try {
            execute(args);
        } catch (NexException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }

    public static void execute(String[] args) {
        if (args != null && args.length > 0 && help(args[0])) {
            System.out.println(uso());
            return;
        }
        if (args == null || args.length == 0) {
            throw new NexException(uso());
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(args);
            case "backup" -> backup(args);
            case "apply" -> apply(args);
            case "export" -> export(args);
            default -> throw new NexException("unknown command: " + args[0] + "\n\n" + uso());
        }
    }

    private static void create(String[] args) {
        String arquivo = null;
        Integer pageSize = null;
        String user = null;
        String password = null;
        String name = null;
        String description = null;
        String charset = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-")) {
                if (arquivo != null) {
                    throw new NexException("file specified more than once");
                }
                arquivo = arg;
                continue;
            }
            String key = arg;
            String value = null;
            int equals = arg.indexOf('=');
            if (equals >= 0) {
                key = arg.substring(0, equals);
                value = arg.substring(equals + 1);
            }
            if (value == null) {
                if (i + 1 >= args.length || args[i + 1].startsWith("-")) {
                    throw new NexException("missing value for " + key);
                }
                value = args[++i];
            }
            switch (normal(key)) {
                case "page-size" -> pageSize = parsePageSize(value);
                case "user" -> user = value;
                case "password" -> password = value;
                case "name" -> name = value;
                case "description" -> description = value;
                case "charset" -> charset = value;
                default -> throw new NexException("unknown option: " + key);
            }
        }
        if (arquivo == null || arquivo.isBlank()) {
            throw new NexException("provide the .db file");
        }
        Path path = Path.of(arquivo);
        DatabaseOptions options = new DatabaseOptions(
                pageSize == null ? DatabaseOptions.defaults().pageSize() : pageSize,
                user == null ? "DBA" : user,
                password == null ? "dba" : password,
                name == null ? fileName(path) : name,
                description == null ? "Nexora" : description,
                charset == null ? "UTF8" : charset);
        Database.create(path, options);
        System.out.println("database created: " + path.toAbsolutePath().normalize());
        System.out.println("page size: " + options.pageSize());
        System.out.println("user: " + options.user());
        System.out.println("name: " + options.name());
    }

    private static void backup(String[] args) {
        String kind = "full";
        int dbAt = 1;
        if (args.length >= 2 && backupKind(args[1]) != null) {
            kind = backupKind(args[1]);
            dbAt = 2;
        }
        if (args.length != dbAt + 2 || args[dbAt].isBlank() || args[dbAt + 1].isBlank()) {
            throw new NexException("""
                    usage: backup full <file.db> <directory>
                    usage: backup incremental <file.db> <directory>""");
        }
        Path database = Path.of(args[dbAt]);
        Path directory = Path.of(args[dbAt + 1]);
        if (kind.equals("incremental")) {
            Database.backupLog(database, directory);
            System.out.println("incremental backup written to: " + directory.toAbsolutePath().normalize());
            return;
        }
        Database.backup(database, directory);
        System.out.println("full backup written to: " + directory.toAbsolutePath().normalize());
    }

    private static String backupKind(String text) {
        String kind = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "full", "incremental" -> kind;
            default -> null;
        };
    }

    private static void apply(String[] args) {
        if (args.length != 3 || args[1].isBlank() || args[2].isBlank()) {
            throw new NexException("usage: apply <file.db> <file.log>");
        }
        Path database = Path.of(args[1]);
        Path log = Path.of(args[2]);
        Database.applyLog(database, log);
        System.out.println("log applied to: " + database.toAbsolutePath().normalize());
    }

    private static void export(String[] args) {
        if (args.length != 3 || args[1].isBlank() || args[2].isBlank()) {
            throw new NexException("usage: export <file.log> <file.sql>");
        }
        Path source = Path.of(args[1]);
        Path sql = Path.of(args[2]);
        int[] shown = {-1};
        Database.exportSql(source, sql, percent -> {
            if (percent == shown[0]) {
                return;
            }
            shown[0] = percent;
            System.out.print("\rexporting: " + percent + "%");
            System.out.flush();
        });
        System.out.println();
        System.out.println("sql written to: " + sql.toAbsolutePath().normalize());
    }

    static String uso() {
        return """
                Usage:
                  java -jar nexora-cli-1.0.jar <command> [arguments]

                Commands:
                  create <file.db>          creates the .db, the .log, and the DBA user
                  backup full <file.db> <directory>         copies the .db and the .log and resets the live log
                  backup incremental <file.db> <directory>  copies only the .log
                  backup <file.db> <directory>              same as full
                  apply <file.db> <file.log>  applies the log onto the .db
                  export <file.log> <file.sql>  writes the SQL from the log

                The page size does not change after creation.
                JDBC and the server open a file that already exists.
                The log stores changes since creation or the last full backup.
                The incremental backup leaves the live log in place.
                export also accepts the .db and uses the .log beside it.

                create options:
                  --page-size <n>          4096, 8192, 16384, or 32768 (default 8192)
                  --user <name>            DBA user (default DBA)
                  --password <password>    DBA password (default dba)
                  --name <name>            database name (default: file name)
                  --description <text>     description (default Nexora)
                  --charset <set>          default UTF8

                Example:
                  java -jar nexora-cli-1.0.jar create C:/data/store.db --page-size 16384 --password secret --name STORE
                  java -jar nexora-cli-1.0.jar backup full C:/data/store.db C:/data/backup
                  java -jar nexora-cli-1.0.jar backup incremental C:/data/store.db C:/data/logs
                  java -jar nexora-cli-1.0.jar apply C:/data/backup/store.db C:/data/store.log
                  java -jar nexora-cli-1.0.jar export C:/data/store.log C:/data/store.sql
                """;
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

    private static int parsePageSize(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new NexException("invalid page size: use 4096, 8192, 16384, or 32768");
        }
    }

    private static String fileName(Path file) {
        Path nome = file.getFileName();
        if (nome == null) {
            return "NEXORA";
        }
        String stem = nome.toString();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        if (stem.isBlank()) {
            return "NEXORA";
        }
        return stem;
    }
}
