package io.github.gabrielmmoraes1999.seadb.dbsrv.console;

import io.github.gabrielmmoraes1999.seadb.Cache;
import io.github.gabrielmmoraes1999.seadb.SeaException;
import io.github.gabrielmmoraes1999.seadb.dbsrv.MotorConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Command line shared by the personal server and the network server.
 * A {@code -n} after a database file is the name in the JDBC URL.
 * {@code file.db=name} does the same in one argument.
 */
public record Arguments(
        Kind kind,
        String host,
        int port,
        String cache,
        Path applyLog,
        List<MotorConfig.DatabaseSpec> databases,
        boolean help
) {

    public static final int DEFAULT_PORT = 4580;
    public static final String DEFAULT_CACHE = "8M";

    public long cacheBytes() {
        return Cache.bytes(cache);
    }

    public static Arguments parse(Kind kind, String[] args) {
        if (kind == null) {
            throw new SeaException("server kind is required");
        }
        if (args == null) {
            args = new String[0];
        }
        String host = null;
        Integer port = null;
        String cache = null;
        String applyText = null;
        List<MotorConfig.DatabaseSpec> databases = new ArrayList<>();
        boolean named = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) {
                throw new SeaException("missing argument\n\n" + usage(kind));
            }
            if (help(arg)) {
                return new Arguments(kind, kind.defaultHost(), DEFAULT_PORT, DEFAULT_CACHE, null, List.of(), true);
            }
            if (arg.startsWith("-")) {
                switch (arg) {
                    case "-n", "--name" -> {
                        String value = value(args, ++i, "name", kind);
                        if (databases.isEmpty() || named) {
                            throw new SeaException("the name follows the database file\n\n" + usage(kind));
                        }
                        MotorConfig.DatabaseSpec current = databases.get(databases.size() - 1);
                        databases.set(databases.size() - 1, new MotorConfig.DatabaseSpec(value, current.path()));
                        named = true;
                    }
                    case "-p", "--port" -> port = port(value(args, ++i, "port", kind), kind);
                    case "--host" -> host = value(args, ++i, "host", kind);
                    case "-c", "--cache" -> cache = cache(value(args, ++i, "cache size", kind), kind);
                    case "-a" -> {
                        if (applyText != null) {
                            throw new SeaException("apply log accepts one file\n\n" + usage(kind));
                        }
                        applyText = value(args, ++i, "transaction log", kind);
                    }
                    case "-x" -> {
                        Link link = link(value(args, ++i, "link", kind), kind);
                        if (link.host != null) {
                            host = link.host;
                        }
                        if (link.port != null) {
                            port = link.port;
                        }
                    }
                    default -> throw new SeaException("unknown option: " + arg + "\n\n" + usage(kind));
                }
                continue;
            }
            databases.add(database(arg, kind));
            named = explicitName(arg);
        }
        if (host == null || host.isBlank()) {
            host = kind.defaultHost();
        }
        host = host.trim();
        if (port == null) {
            port = DEFAULT_PORT;
        }
        if (kind == Kind.PERSONAL && !local(host)) {
            throw new SeaException("""
                    the personal server listens on this machine only
                    use the network server to accept connections from other machines

                    """ + usage(kind));
        }
        if (cache == null || cache.isBlank()) {
            cache = DEFAULT_CACHE;
        }
        Path applyLog = null;
        if (applyText != null) {
            if (databases.size() != 1) {
                throw new SeaException("apply log needs one database\n\n" + usage(kind));
            }
            applyLog = Path.of(applyText);
            if (!applyLog.isAbsolute()) {
                Path parent = databases.get(0).path().getParent();
                applyLog = parent == null ? applyLog : parent.resolve(applyLog);
            }
            applyLog = applyLog.toAbsolutePath().normalize();
        }
        distinct(databases);
        return new Arguments(kind, host, port, cache, applyLog, List.copyOf(databases), false);
    }

    public static String usage(Kind kind) {
        String jar = kind == null ? "seadb-dbeng-1.0.jar" : kind.jar();
        String where = kind == Kind.NETWORK
                ? """
                The network server listens on every interface unless --host says otherwise.
                Clients connect with jdbc:seadb://<host>:<port>/<name>.
                """
                : """
                The personal server listens on 127.0.0.1. Programs on this machine connect
                with jdbc:seadb://127.0.0.1:<port>/<name>. The .db stays open in this process.
                """;
        String host = kind == Kind.NETWORK
                ? "Bind address. The default is every interface."
                : "Only 127.0.0.1, localhost, or ::1.";
        return """
                Usage:
                  java -jar %s [options] [database.db ...]

                %s
                Options:
                  -n, --name <name>       Name in the JDBC URL. It follows the database file.
                  -c, --cache <size>      Maximum page cache, such as 8M or 2048K. The default is 8M.
                  -a <log>                Apply the transaction log, then exit.
                  -p, --port <port>       TCP port. The default is 4580.
                  -x tcpip(port=<port>)   TCP port, written as tcpip(port=<port>).
                  --host <address>        %s
                  --help                  Show this text.

                file.db=name publishes that file under name.
                With no database, the first window asks for the file, the cache size, and the options.
                That window remembers the last values when the command line sets none.
                """.formatted(jar, where.strip(), host);
    }

    public static String stem(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name;
    }

    private static boolean explicitName(String arg) {
        String pathText = arg.trim();
        int eq = pathText.lastIndexOf('=');
        if (eq <= 0 || eq >= pathText.length() - 1) {
            return false;
        }
        String right = pathText.substring(eq + 1).trim();
        return !right.isBlank() && !right.contains("/") && !right.contains("\\") && !right.contains(":");
    }

    private static MotorConfig.DatabaseSpec database(String arg, Kind kind) {
        String pathText = arg.trim();
        String explicit = null;
        if (explicitName(pathText)) {
            int eq = pathText.lastIndexOf('=');
            explicit = pathText.substring(eq + 1).trim();
            pathText = pathText.substring(0, eq).trim();
        }
        if (pathText.isBlank()) {
            throw new SeaException("database path is required\n\n" + usage(kind));
        }
        Path path = Path.of(pathText);
        String name = explicit == null || explicit.isBlank() ? stem(path) : explicit;
        if (name.isBlank()) {
            throw new SeaException("database name is required\n\n" + usage(kind));
        }
        return new MotorConfig.DatabaseSpec(name, path);
    }

    private static void distinct(List<MotorConfig.DatabaseSpec> databases) {
        Set<String> names = new HashSet<>();
        for (MotorConfig.DatabaseSpec spec : databases) {
            if (spec.name() == null || spec.name().isBlank()) {
                throw new SeaException("database name is required");
            }
            if (!names.add(spec.name().toLowerCase(Locale.ROOT))) {
                throw new SeaException("duplicate database name: " + spec.name());
            }
        }
    }

    private static boolean local(String host) {
        return host.equals("127.0.0.1")
                || host.equalsIgnoreCase("localhost")
                || host.equals("::1")
                || host.equals("[::1]");
    }

    private static boolean help(String arg) {
        return arg.equals("--help") || arg.equals("-h") || arg.equals("-?");
    }

    private static String value(String[] args, int index, String kindName, Kind kind) {
        if (index >= args.length || args[index] == null || args[index].isBlank() || args[index].startsWith("-")) {
            throw new SeaException(kindName + " is required\n\n" + usage(kind));
        }
        return args[index].trim();
    }

    static String cache(String text, Kind kind) {
        String raw = text.trim().replace(" ", "").toUpperCase(Locale.ROOT);
        String suffix = "M";
        String number = raw;
        if (raw.endsWith("MB")) {
            number = raw.substring(0, raw.length() - 2);
        } else if (raw.endsWith("KB")) {
            number = raw.substring(0, raw.length() - 2);
            suffix = "K";
        } else if (raw.endsWith("M")) {
            number = raw.substring(0, raw.length() - 1);
        } else if (raw.endsWith("K")) {
            number = raw.substring(0, raw.length() - 1);
            suffix = "K";
        }
        if (number.isEmpty()) {
            throw new SeaException("invalid cache size: " + text + "\n\n" + usage(kind));
        }
        try {
            long value = Long.parseLong(number);
            if (value < 1 || value > 1_048_576L) {
                throw new SeaException("invalid cache size: " + text + "\n\n" + usage(kind));
            }
            return value + suffix;
        } catch (NumberFormatException exception) {
            throw new SeaException("invalid cache size: " + text + "\n\n" + usage(kind));
        }
    }

    private static int port(String text, Kind kind) {
        try {
            int value = Integer.parseInt(text);
            if (value < 1 || value > 65535) {
                throw new SeaException("invalid port: " + text + "\n\n" + usage(kind));
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new SeaException("invalid port: " + text + "\n\n" + usage(kind));
        }
    }

    private static Link link(String text, Kind kind) {
        String value = text.trim();
        if (!value.toLowerCase(Locale.ROOT).startsWith("tcpip")) {
            throw new SeaException("unsupported link: " + text + "\n\n" + usage(kind));
        }
        String rest = value.substring("tcpip".length()).trim();
        if (rest.isEmpty()) {
            return new Link(null, null);
        }
        if (!rest.startsWith("(") || !rest.endsWith(")")) {
            throw new SeaException("unsupported link: " + text + "\n\n" + usage(kind));
        }
        rest = rest.substring(1, rest.length() - 1).trim();
        String host = null;
        Integer port = null;
        if (!rest.isEmpty()) {
            for (String part : rest.split("[;,]")) {
                String item = part.trim();
                if (item.isEmpty()) {
                    continue;
                }
                int eq = item.indexOf('=');
                if (eq <= 0 || eq == item.length() - 1) {
                    throw new SeaException("unsupported link: " + text + "\n\n" + usage(kind));
                }
                String key = item.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String setting = item.substring(eq + 1).trim();
                switch (key) {
                    case "port" -> port = port(setting, kind);
                    case "host" -> host = setting;
                    default -> throw new SeaException("unsupported link: " + text + "\n\n" + usage(kind));
                }
            }
        }
        return new Link(host, port);
    }

    private record Link(String host, Integer port) {
    }
}
