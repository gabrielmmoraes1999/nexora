package io.github.gabrielmmoraes1999.seadb.dbsrv.console;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Last database, cache size, and options confirmed on the startup window.
 * The personal server uses {@code ~/.seadb/dbeng.properties}.
 * The network server uses {@code ~/.seadb/dbsrv.properties}.
 */
public final class VisualConfig {

    /**
     * The three fields of the startup window, as the user left them.
     */
    public record Fields(String database, String cache, String options) {
    }

    private VisualConfig() {
    }

    public static Path file(Kind kind) {
        String name = kind == Kind.PERSONAL ? "dbeng.properties" : "dbsrv.properties";
        return Path.of(System.getProperty("user.home"), ".seadb", name);
    }

    /**
     * @return the saved fields, or null when the file is missing or unreadable
     */
    public static Fields load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
        return new Fields(
                text(properties, "database"),
                text(properties, "cache"),
                text(properties, "options"));
    }

    public static void save(Path file, Fields fields) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("database", fields.database() == null ? "" : fields.database());
        properties.setProperty("cache", fields.cache() == null ? "" : fields.cache());
        properties.setProperty("options", fields.options() == null ? "" : fields.options());
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "Last startup window");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The window keeps the saved fields when the command line did not choose a database or an option.
     */
    static boolean plain(Arguments arguments) {
        return arguments.databases().isEmpty()
                && arguments.applyLog() == null
                && arguments.port() == Arguments.DEFAULT_PORT
                && Arguments.DEFAULT_CACHE.equals(arguments.cache())
                && arguments.kind().defaultHost().equals(arguments.host());
    }

    /**
     * Fields shown when the window opens. A plain command line uses the saved fields.
     */
    static Fields display(Arguments preset, Fields saved) {
        if (saved != null && plain(preset)) {
            return new Fields(blank(saved.database()), cacheOf(saved.cache()), blank(saved.options()));
        }
        String database = "";
        if (preset.databases().size() == 1) {
            database = preset.databases().get(0).path().toString();
        }
        return new Fields(database, cacheOf(preset.cache()), StartDialog.optionsOf(preset));
    }

    private static String cacheOf(String cache) {
        return cache == null || cache.isBlank() ? Arguments.DEFAULT_CACHE : cache;
    }

    private static String blank(String text) {
        return text == null ? "" : text;
    }

    private static String text(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null ? "" : value;
    }
}
