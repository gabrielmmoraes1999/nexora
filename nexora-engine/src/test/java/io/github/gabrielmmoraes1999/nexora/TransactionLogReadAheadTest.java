package io.github.gabrielmmoraes1999.nexora;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TransactionLogReadAheadTest {
    @TempDir
    Path dir;

    private byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    @Test
    void recoveryHandlesWindowBoundariesLargeRecordsUndoAndCommittedSql() {
        Path database = dir.resolve("window.db");
        byte[] small = randomBytes(32 * 1024 + 13);
        byte[] large = randomBytes(1024 * 1024 + 37);
        try (TransactionLog log = TransactionLog.create(database, 1, 8192, 1)) {
            for (int i = 0; i < 96; i++) {
                log.appendRedo(List.of(new TransactionLog.Span((long) i * small.length, small.length, small)), 10_000_000);
            }
            log.appendRedo(List.of(new TransactionLog.Span((long) 96 * small.length, large.length, large)), 10_000_000);
            log.appendCheckpoint();
            log.force();
            long checkpoint = log.size();
            TransactionLog.TxnUndo undo = new TransactionLog.TxnUndo(7, List.of(), List.of(
                    new TransactionLog.TableUndo("public", "t", new long[]{3},
                            List.of(new TransactionLog.RowImage(1, new Object[]{large})), List.of())));
            log.appendUndo(undo);
            log.appendCheckpoint(List.of(undo));
            log.appendTxnSql(8, List.of(new TransactionLog.Applied("public", "INSERT INTO t VALUES (?)", new Object[]{42})));
            log.appendCommit(8);
            log.force();

            AtomicInteger replayed = new AtomicInteger();
            assertTrue(log.replay(Format.LOG_HEADER_SIZE, (size, spans) -> {
                assertEquals(10_000_000L, size);
                assertEquals(1, spans.size());
                assertArrayEquals(replayed.getAndIncrement() < 96 ? small : large, spans.get(0).data);
            }));
            assertEquals(97, replayed.get());
            assertEquals(log.size(), log.consistentEnd());
            List<TransactionLog.TxnUndo> active = log.transactionsToUndo(checkpoint);
            assertEquals(1, active.size());
            assertEquals(7, active.get(0).id);
            assertArrayEquals(large, (byte[]) active.get(0).tables.get(0).updated.get(0).values[0]);
            var sql = log.sqlToReplay(checkpoint);
            assertEquals(1, sql.size());
            assertEquals("INSERT INTO t VALUES (?)", sql.get(0).statements.get(0).sql);
        }
    }

    @Test
    void historicalCorruptionStillFailsEveryRecoveryScan() throws Exception {
        Path database = dir.resolve("corrupt.db");
        byte[] large = randomBytes(1024 * 1024 + 37);
        long checkpoint;
        try (TransactionLog log = TransactionLog.create(database, 1, 8192, 1)) {
            log.appendRedo(List.of(new TransactionLog.Span(0, large.length, large)), large.length);
            log.appendCheckpoint();
            log.force();
            checkpoint = log.size();
        }
        Path path = TransactionLog.beside(database);
        byte[] bytes = Files.readAllBytes(path);
        bytes[Format.LOG_HEADER_SIZE + 20] ^= 1;
        Files.write(path, bytes);
        try (TransactionLog log = TransactionLog.open(database, 1, 8192)) {
            assertThrows(NexException.class, () -> log.replay(checkpoint, (size, spans) -> fail("old redo must not be applied")));
            assertThrows(NexException.class, () -> log.transactionsToUndo(checkpoint));
            assertThrows(NexException.class, () -> log.sqlToReplay(checkpoint));
        }
    }

    @Test
    void tornTailBeyondTheWindowKeepsTheIntactPrefix() throws Exception {
        Path database = dir.resolve("torn.db");
        byte[] large = randomBytes(1024 * 1024 + 37);
        long checkpoint;
        try (TransactionLog log = TransactionLog.create(database, 1, 8192, 1)) {
            log.appendRedo(List.of(new TransactionLog.Span(0, large.length, large)), large.length);
            log.appendCheckpoint();
            log.force();
            checkpoint = log.size();
        }
        Files.write(TransactionLog.beside(database), new byte[]{0, 0, 0, 100, 1}, StandardOpenOption.APPEND);
        try (TransactionLog log = TransactionLog.open(database, 1, 8192)) {
            assertFalse(log.replay(checkpoint, (size, spans) -> fail("old redo must not be applied")));
            assertEquals(checkpoint, log.consistentEnd());
            assertTrue(log.transactionsToUndo(checkpoint).isEmpty());
            assertTrue(log.sqlToReplay(checkpoint).isEmpty());
        }
    }
}
