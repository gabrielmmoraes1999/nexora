package io.github.gabrielmmoraes1999.nexora.dbtran;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.NexException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/**
 * Translates a transaction log into SQL.
 * {@code source} may be the {@code .log} or the {@code .db} beside it.
 * Parameters are written into the text in place of each {@code ?}.
 */
public final class DbtranService {

    private DbtranService() {
    }

    /**
     * Reads the compressed transactions and writes the SQL to {@code destination}.
     *
     * @param source a {@code .log} path or the {@code .db} beside it
     * @return the same line the CLI prints when the SQL has been written
     */
    public static String export(String source, Path destination, IntConsumer progress) {
        if (source == null || source.isBlank()) {
            throw new NexException("provide a .log file or the .db beside it");
        }
        if (destination == null) {
            throw new NexException("the .sql file is required");
        }
        Database.exportSql(path(source), destination, progress);
        return "sql written to: " + destination.toAbsolutePath().normalize();
    }

    private static Path path(String text) {
        try {
            return Path.of(text.trim());
        } catch (InvalidPathException exception) {
            throw new NexException("invalid path: " + text.trim());
        }
    }
}
