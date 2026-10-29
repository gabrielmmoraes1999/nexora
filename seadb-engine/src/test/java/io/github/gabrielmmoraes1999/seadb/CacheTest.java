package io.github.gabrielmmoraes1999.seadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheTest {

    @TempDir
    Path dir;

    @Test
    void bytesAndPageAdjustment() {
        assertEquals(8L * 1024 * 1024, Cache.bytes("8M"));
        assertEquals(2048L * 1024, Cache.bytes("2048K"));
        assertEquals(32L * 1024 * 1024, Cache.bytes("32"));
        assertEquals(8192L, Cache.adjust(1000, 8192));
        assertEquals(16384L, Cache.adjust(10000, 8192));
        assertEquals(8L * 1024 * 1024, Cache.adjust(8L * 1024 * 1024, 8192));
        assertThrows(SeaException.class, () -> Cache.bytes("0"));
        assertThrows(SeaException.class, () -> Cache.of(0));
    }

    @Test
    void dropsTheOldestPageWhenThePoolIsFull() throws Exception {
        Path file = dir.resolve("pages.bin");
        int page = 4096;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            for (int i = 0; i < 3; i++) {
                byte[] block = new byte[page];
                block[0] = (byte) (i + 1);
                Format.writeFully(channel, (long) i * page, block);
            }
            Cache cache = Cache.of(page * 2L);
            cache.accept(page);
            assertEquals(page * 2L, cache.maximum());
            assertEquals(1, cache.read(file, channel, page, 0, 1)[0]);
            assertEquals(2, cache.read(file, channel, page, page, 1)[0]);
            assertEquals(2, cache.pages());
            assertEquals(3, cache.read(file, channel, page, page * 2L, 1)[0]);
            assertTrue(cache.pages() <= 2);
            assertTrue(cache.resident() <= cache.maximum());
            byte[] changed = new byte[page];
            changed[0] = 9;
            Format.writeFully(channel, 0, changed);
            cache.forget(file, page, 0, page);
            assertEquals(9, cache.read(file, channel, page, 0, 1)[0]);
            assertTrue(cache.resident() <= cache.maximum());
        }
    }

    @Test
    void databaseRespectsAOnePageCache() {
        Path file = dir.resolve("tiny.db");
        Database.create(file, DatabaseOptions.defaults());
        String first = "A".repeat(3000);
        String second = "B".repeat(3000);
        Cache cache = Cache.of(8192);
        try (Database db = Database.open(file, "dba", "dba", cache)) {
            assertEquals(8192L, db.cacheMaximum());
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, note VARCHAR(5000))");
            db.execute("INSERT INTO t VALUES (1, ?)", first);
            db.execute("INSERT INTO t VALUES (2, ?)", second);
            assertEquals(first, db.query("SELECT note FROM t WHERE id = 1").rows().get(0).getString("note"));
            assertEquals(second, db.query("SELECT note FROM t WHERE id = 2").rows().get(0).getString("note"));
            assertTrue(cache.resident() <= cache.maximum());
            assertTrue(cache.pages() <= 1);
        }
        Cache again = Cache.of(8192);
        try (Database db = Database.open(file, "dba", "dba", again)) {
            assertEquals(first, db.query("SELECT note FROM t WHERE id = 1").rows().get(0).getString("note"));
            assertEquals(second, db.query("SELECT note FROM t WHERE id = 2").rows().get(0).getString("note"));
            assertTrue(again.resident() <= again.maximum());
        }
    }
}
