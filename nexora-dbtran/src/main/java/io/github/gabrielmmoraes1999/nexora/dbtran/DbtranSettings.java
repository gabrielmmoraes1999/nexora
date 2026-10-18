package io.github.gabrielmmoraes1999.nexora.dbtran;

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
 * Last fields of the log translation window.
 * The file is {@code ~/.nexora/dbtran.properties}.
 */
public record DbtranSettings(String source, String sql) {

    public static Path file() {
        return Path.of(System.getProperty("user.home"), ".nexora", "dbtran.properties");
    }

    /**
     * @return the saved fields, or null when the file is missing or unreadable
     */
    public static DbtranSettings load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
        return new DbtranSettings(text(properties, "source"), text(properties, "sql"));
    }

    public static void save(Path file, DbtranSettings settings) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("source", blank(settings.source()));
        properties.setProperty("sql", blank(settings.sql()));
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "Nexora dbtran");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String blank(String text) {
        return text == null ? "" : text;
    }

    private static String text(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null ? "" : value;
    }
}
