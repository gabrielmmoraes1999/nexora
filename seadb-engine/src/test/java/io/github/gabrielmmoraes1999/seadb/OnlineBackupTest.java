package io.github.gabrielmmoraes1999.seadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OnlineBackupTest {
    @TempDir Path dir;

    @Test
    void snapshotSurvivesUpdatesCheckpointsAndCanReplayConcurrentCommits() throws Exception {
        Path file = dir.resolve("live.db");
        Path copy = Files.createDirectory(dir.resolve("copy"));
        Database.create(file, DatabaseOptions.defaults());
        try (Database db = Database.open(file, "dba", "dba")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name VARCHAR(40))");
            db.execute("INSERT INTO t VALUES (1, 'before')");
            List<BackupPiece> pieces = db.beginBackup(db.session(), false);
            byte[] originalDb = read(db, 0, pieces.get(0).size());
            byte[] originalLog = read(db, 1, pieces.get(1).size());
            db.execute("UPDATE t SET name = 'after' WHERE id = 1");
            db.execute("INSERT INTO t VALUES (2, 'concurrent')");
            db.checkpoint();
            for (int i = 0; i < pieces.size(); i++) {
                byte[] bytes = read(db, i, pieces.get(i).size());
                assertArrayEquals(i == 0 ? originalDb : originalLog, bytes);
                Files.write(copy.resolve(pieces.get(i).name()), bytes);
            }
            db.finishBackup(db.session());
            assertTrue(Files.size(dir.resolve("live.log")) > originalLog.length);
            // A second incremental snapshot also excludes subsequent writes.
            pieces = db.beginBackup(db.session(), true);
            byte[] prefix = read(db, 0, pieces.get(0).size());
            db.execute("INSERT INTO t VALUES (3, 'later')");
            assertArrayEquals(prefix, read(db, 0, pieces.get(0).size()));
            db.abortBackup(db.session());
        }
        // Opening a database writes new checkpoints. Inspect a separate copy so
        // incremental recovery starts from the original backup log position.
        Path inspect = Files.createDirectory(dir.resolve("inspect"));
        Files.copy(copy.resolve("live.db"), inspect.resolve("live.db"));
        Files.copy(copy.resolve("live.log"), inspect.resolve("live.log"));
        try (Database db = Database.open(inspect.resolve("live.db"), "dba", "dba")) {
            assertEquals("before", db.query("SELECT name FROM t WHERE id = 1").rows().get(0).getString("name"));
            assertEquals(1, db.query("SELECT * FROM t").rows().size());
        }
        Database.applyLog(copy.resolve("live.db"), dir.resolve("live.log"));
        try (Database db = Database.open(copy.resolve("live.db"), "dba", "dba")) {
            assertEquals("after", db.query("SELECT name FROM t WHERE id = 1").rows().get(0).getString("name"));
            assertEquals(3, db.query("SELECT * FROM t").rows().size());
        }
    }

    @Test
    void openTransactionCanContinueAndRollbackWhileSnapshotIsRead() throws Exception {
        Path file = dir.resolve("transaction.db");
        Path copy = Files.createDirectory(dir.resolve("transaction-copy"));
        Database.create(file, DatabaseOptions.defaults());
        try (Database db = Database.open(file, "dba", "dba")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            db.execute("INSERT INTO t VALUES (1)");
            db.execute("BEGIN");
            db.execute("INSERT INTO t VALUES (2)");
            List<BackupPiece> pieces = db.beginBackup(db.session(), false);
            byte[] original = read(db, 0, pieces.get(0).size());
            db.checkpointPolicy(new CheckpointPolicy(0, 1, 1, 1, 2));
            for (int i = 3; i < 103; i++) db.execute("INSERT INTO t VALUES (" + i + ")");
            db.execute("ROLLBACK");
            db.checkpoint();
            assertArrayEquals(original, read(db, 0, pieces.get(0).size()));
            for (int i = 0; i < pieces.size(); i++) {
                Files.write(copy.resolve(pieces.get(i).name()), read(db, i, pieces.get(i).size()));
            }
            db.finishBackup(db.session());
            assertEquals(1, db.query("SELECT * FROM t").rows().size());
            // Aborting and starting again must release the previous snapshot.
            db.beginBackup(db.session(), false);
            db.execute("INSERT INTO t VALUES (200)");
            db.abortBackup(db.session());
            db.beginBackup(db.session(), false);
            db.finishBackup(db.session());
        }
        // The backup captured uncommitted pages: recovery must undo them.
        Database.applyLog(copy.resolve("transaction.db"), copy.resolve("transaction.log"));
        try (Database db = Database.open(copy.resolve("transaction.db"), "dba", "dba")) {
            assertEquals(1, db.query("SELECT * FROM t").rows().size());
        }
    }

    @Test
    void beforeImagesSurviveTruncationAndRepeatedUnalignedReads() throws Exception {
        Path file = dir.resolve("pages");
        byte[] original = new byte[200_003];
        new java.util.Random(42).nextBytes(original);
        Files.write(file, original);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
             BackupSnapshot snapshot = new BackupSnapshot(channel, original.length)) {
            snapshot.preserve(65_530, 20);
            Format.writeFully(channel, 65_530, new byte[20]);
            snapshot.preserve(100_000, original.length - 100_000);
            channel.truncate(100_000);
            assertArrayEquals(original, snapshot.read(0, original.length));
            assertArrayEquals(Arrays.copyOfRange(original, 65_531, 150_001), snapshot.read(65_531, 84_470));
        }
    }

    private byte[] read(Database db, int index, long size) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        for (long offset = 0; offset < size;) {
            byte[] bytes = db.readBackup(db.session(), index, offset, (int) Math.min(17_003, size - offset));
            out.write(bytes);
            offset += bytes.length;
        }
        return out.toByteArray();
    }
}
