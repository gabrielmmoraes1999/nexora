package io.github.gabrielmmoraes1999.nexora.backup;

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
 * Last fields of the backup window.
 * The file is {@code ~/.nexora/backup.properties}.
 * The password is stored only when the user asked to remember it.
 */
public record BackupSettings(
        String operation,
        String source,
        String user,
        String password,
        boolean remember,
        String role,
        String directory) {

    public static Path file() {
        return Path.of(System.getProperty("user.home"), ".nexora", "backup.properties");
    }

    /**
     * @return the saved fields, or null when the file is missing or unreadable
     */
    public static BackupSettings load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
        boolean remember = Boolean.parseBoolean(text(properties, "remember"));
        return new BackupSettings(
                text(properties, "operation"),
                text(properties, "source"),
                text(properties, "user"),
                remember ? text(properties, "password") : "",
                remember,
                text(properties, "role"),
                text(properties, "directory"));
    }

    public static void save(Path file, BackupSettings settings) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("operation", blank(settings.operation()));
        properties.setProperty("source", blank(settings.source()));
        properties.setProperty("user", blank(settings.user()));
        properties.setProperty("password", settings.remember() ? blank(settings.password()) : "");
        properties.setProperty("remember", Boolean.toString(settings.remember()));
        properties.setProperty("role", blank(settings.role()));
        properties.setProperty("directory", blank(settings.directory()));
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "Nexora Backup");
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
