package io.github.gabrielmmoraes1999.seadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogApplyProgressTest {
    @TempDir
    Path dir;

    private Path database(String name) {
        Path file = dir.resolve(name + ".db");
        Database.create(file, DatabaseOptions.defaults());
        try (Database db = Database.open(file, "dba", "dba")) {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY)");
            db.execute("INSERT INTO item VALUES (1)");
        }
        return file;
    }

    @Test
    void reportsCheckpointEvenWhenThereAreNoSqlBatchesToReplay() {
        Path file = database("clean");
        List<String> messages = new ArrayList<>();
        Database.applyLog(file, TransactionLog.beside(file), null, null, messages::add);
        assertTrue(messages.getFirst().startsWith("Recovering from checkpoint at log offset "));
        assertTrue(messages.stream().anyMatch(text -> text.startsWith("Checkpoint read at log offset ")));
        assertTrue(messages.getLast().startsWith("Checkpoint applied at log offset "));
        try (Database db = Database.open(file, "dba", "dba")) {
            assertEquals(1, db.query("SELECT COUNT(*) FROM item").rows().get(0).getLong(0));
        }
    }

    @Test
    void reportsUndoBeforeCompletionAndRecoversUncommittedPages() {
        Path file = database("crashed");
        Database db = Database.open(file, "dba", "dba");
        try {
            db.checkpointPolicy(CheckpointPolicy.MANUAL);
            db.execute("BEGIN");
            db.execute("INSERT INTO item VALUES (2)");
            db.checkpoint();
            db.crash();
        } finally {
            db.close();
        }
        List<String> messages = new ArrayList<>();
        Database.applyLog(file, TransactionLog.beside(file), null, null, messages::add);
        int undo = messages.indexOf("Undoing uncommitted transactions");
        int committed = messages.indexOf("Recovering committed transactions");
        assertTrue(undo >= 0 && committed > undo);
        assertTrue(messages.getLast().startsWith("Checkpoint applied at log offset "));
        try (Database recovered = Database.open(file, "dba", "dba")) {
            assertEquals(1, recovered.query("SELECT COUNT(*) FROM item").rows().get(0).getLong(0));
        }
    }

    @Test
    void rejectedLogDoesNotReportAnAppliedCheckpoint() {
        Path file = database("target");
        Path other = database("other");
        List<String> messages = new ArrayList<>();
        assertThrows(SeaException.class, () -> Database.applyLog(file, TransactionLog.beside(other),
                null, null, messages::add));
        assertFalse(messages.stream().anyMatch(text -> text.startsWith("Checkpoint applied")));
    }
}
