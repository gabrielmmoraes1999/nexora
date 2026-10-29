package io.github.gabrielmmoraes1999.seadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiskRowTest {
    @TempDir
    Path dir;

    private Path createTable() {
        Path path = dir.resolve("rows.db");
        Database.create(path, DatabaseOptions.defaults());
        try (Database db = Database.open(path, "dba", "dba")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, note VARCHAR(8000), amount DECIMAL(20, 4))");
            db.execute("BEGIN");
            for (int i = 1; i <= 64; i++) {
                db.execute("INSERT INTO t VALUES (?, ?, ?)", i, Integer.toString(i) + "x".repeat(7000), "12.3456");
            }
            db.execute("COMMIT");
        }
        return path;
    }

    private TableStore table(DatabaseFile file) {
        return file.tables().stream().filter(region -> region.name().equalsIgnoreCase("t"))
                .map(region -> TableStore.open(file, region)).findFirst().orElseThrow();
    }

    @Test
    void openingReadsMetadataAndScanningKeepsThePageCacheBounded() {
        Path path = createTable();
        Cache cache = Cache.of(4 * 8192L);
        try (DatabaseFile file = DatabaseFile.open(path, cache)) {
            cache.release(path);
            TableStore table = table(file);
            assertEquals(64, table.rowMap().size());
            assertTrue(cache.resident() < cache.maximum(), "opening must not read all row pages");
            for (StoredRow row : table.scan()) {
                assertEquals(Integer.toString((int) row.id) + "x".repeat(7000), table.value(row, 1));
                assertEquals("12.3456", table.value(row, 2).toString());
            }
            assertTrue(cache.resident() <= cache.maximum());
        }
    }

    @Test
    void decodedRowsAreEvictedButPinnedSnapshotsSurvivePageReuse() {
        Path path = createTable();
        Cache cache = Cache.of(8192);
        try (DatabaseFile file = DatabaseFile.open(path, cache)) {
            TableStore table = table(file);
            StoredRow first = table.row(1);
            StoredRow second = table.row(2);
            assertEquals(1, table.value(first, 0));
            second.pinCells();
            assertEquals(3, table.value(table.row(3), 0));
            // Simulate overwritten slot headers, after evicting the decoded first row.
            Format.writeFully(file.channel, table.base() + first.slotOffset, new byte[4]);
            Format.writeFully(file.channel, table.base() + second.slotOffset, new byte[4]);
            cache.release(path);
            assertThrows(SeaException.class, () -> table.value(first, 0));
            assertEquals(2, table.value(second, 0));
            // A fresh open still reads only metadata. Corruption is checked on access.
            TableStore reopened = table(file);
            assertEquals(64, reopened.rowMap().size());
            assertThrows(SeaException.class, () -> reopened.value(reopened.row(1), 0));
        }
    }

    @Test
    void reopenedRowsSurviveCheckpointRollbackAndPacking() {
        Path path = createTable();
        try (Database db = Database.open(path, "dba", "dba")) {
            db.execute("BEGIN");
            db.execute("UPDATE t SET note = 'temporary', amount = 99 WHERE id = 1");
            db.checkpoint();
            db.execute("ROLLBACK");
            assertEquals("1" + "x".repeat(7000), db.query("SELECT note FROM t WHERE id = 1").rows().get(0).getString(0));
            db.execute("ALTER TABLE t ALTER note POSITION 3");
            db.execute("DELETE FROM t WHERE id > 4");
            db.checkpoint();
        }
        try (Database db = Database.open(path, "dba", "dba")) {
            assertEquals(4L, db.query("SELECT COUNT(*) FROM t").rows().get(0).getLong(0));
            assertEquals("1" + "x".repeat(7000), db.query("SELECT note FROM t WHERE id = 1").rows().get(0).getString(0));
            assertEquals("12.3456", db.query("SELECT amount FROM t WHERE id = 1").rows().get(0).get(0).toString());
        }
    }
}
