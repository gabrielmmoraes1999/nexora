package io.github.gabrielmmoraes1999.nexora.studio;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registro local dos bancos. O arquivo é {@code ~/.nexora/studio.json}.
 */
public final class Registry {

    private Registry() {
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".nexora", "studio.json");
    }

    public static List<DatabaseEntry> load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        Object parsed = Json.parse(Files.readString(file));
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IOException("registro inválido");
        }
        Object raw = root.get("databases");
        if (raw == null) {
            return new ArrayList<>();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IOException("registro inválido");
        }
        List<DatabaseEntry> entries = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IOException("registro inválido");
            }
            entries.add(new DatabaseEntry(
                    text(map, "id"),
                    text(map, "alias"),
                    bool(map, "embedded"),
                    text(map, "path"),
                    text(map, "host"),
                    number(map, "port"),
                    text(map, "database"),
                    text(map, "user"),
                    text(map, "password"),
                    text(map, "role"),
                    bool(map, "rememberPassword")));
        }
        return entries;
    }

    public static void save(Path file, List<DatabaseEntry> entries) throws IOException {
        List<Object> databases = new ArrayList<>();
        for (DatabaseEntry entry : entries) {
            DatabaseEntry stored = entry.forStorage();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", stored.id());
            map.put("alias", stored.alias());
            map.put("embedded", stored.embedded());
            map.put("path", stored.path());
            map.put("host", stored.host());
            map.put("port", stored.port());
            map.put("database", stored.database());
            map.put("user", stored.user());
            map.put("password", stored.password());
            map.put("role", stored.role());
            map.put("rememberPassword", stored.rememberPassword());
            databases.add(map);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("databases", databases);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, Json.write(root));
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean bool && bool;
    }

    private static int number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
