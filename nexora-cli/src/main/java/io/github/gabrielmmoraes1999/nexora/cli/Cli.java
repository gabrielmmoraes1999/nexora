package io.github.gabrielmmoraes1999.nexora.cli;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.DatabaseOptions;
import io.github.gabrielmmoraes1999.nexora.NexException;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Nexora command line. Creates the {@code .db}.
 * JDBC and the server open a file that already exists.
 *
 * <pre>{@code
 * java -jar nexora-cli-1.0.jar create store.db --page-size 8192 --user DBA --password secret
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
        if (!args[0].equalsIgnoreCase("create")) {
            throw new NexException("unknown command: " + args[0] + "\n\n" + uso());
        }
        create(args);
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

    static String uso() {
        return """
                Usage:
                  java -jar nexora-cli-1.0.jar create <file.db> [options]

                Creates the .db, the .log, and the DBA user.
                The page size does not change after creation.
                JDBC and the server open a file that already exists.

                Options:
                  --page-size <n>          4096, 8192, 16384, or 32768 (default 8192)
                  --user <name>            DBA user (default DBA)
                  --password <password>    DBA password (default dba)
                  --name <name>            database name (default: file name)
                  --description <text>     description (default Nexora)
                  --charset <set>          default UTF8

                Example:
                  java -jar nexora-cli-1.0.jar create C:/data/store.db --page-size 16384 --password secret --name STORE
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
