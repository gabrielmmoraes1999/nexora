package io.github.gabrielmmoraes1999.seadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointCommitTest {
    @TempDir
    Path dir;

    @Test
    void crescimentoEmSegundoPlanoPreservaDadosNoCommit() throws Exception {
        relocateInBackground("COMMIT");
    }

    @Test
    void crescimentoEmSegundoPlanoPreservaDadosNoRollback() throws Exception {
        relocateInBackground("ROLLBACK");
    }

    @Test
    void crescimentoEmSegundoPlanoPreservaDadosNaRecuperacao() throws Exception {
        relocateInBackground("CRASH");
    }

    private void relocateInBackground(String outcome) throws Exception {
        Path file = dir.resolve("growth.db");
        Database.create(file, DatabaseOptions.defaults());
        try (Database db = Database.open(file, "dba", "dba")) {
            db.checkpointPolicy(CheckpointPolicy.MANUAL);
            db.execute("CREATE TABLE growing (id INTEGER PRIMARY KEY, payload VARCHAR(2000))");
            db.execute("INSERT INTO growing VALUES (0, 'original')");
            // Occupy the following region so growing cannot extend in place.
            db.execute("CREATE TABLE blocker (id INTEGER PRIMARY KEY, payload VARCHAR(2000))");
            db.execute("INSERT INTO blocker VALUES (0, ?)", "b".repeat(1500));
            db.execute("BEGIN");
            for (int i = 1; i <= 200; i++) {
                db.execute("INSERT INTO growing VALUES (?, ?)", i, "x".repeat(1500));
            }
            Engine engine = engine(db);
            Field storageField = Engine.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            DatabaseFile storage = (DatabaseFile) storageField.get(engine);
            long mark = storage.logLength();
            List<TableStore> frozen = freeze(db);
            TableStore growing = frozen.stream().filter(t -> t.schema().name.equalsIgnoreCase("growing"))
                    .findFirst().orElseThrow();
            long oldBase = growing.base();
            frozen.forEach(TableStore::encodePrepared);
            Field prepared = TableStore.class.getDeclaredField("preparedPlan");
            prepared.setAccessible(true);
            TableStore.Plan plan = (TableStore.Plan) prepared.get(growing);
            assertNotNull(plan.relocation);
            assertTrue(plan.fileSize > growing.capacity());
            assertEquals(oldBase, plan.sourceBase);
            // Inserts arriving after encoding must survive both publication and commit.
            db.execute("INSERT INTO growing VALUES (201, 'late')");
            storage.beginDeferredSync();
            try {
                db.checkpoint();
            } finally {
                storage.endDeferredSync();
            }
            storage.limitDeferredCheckpoint(mark);
            storage.forceLog();
            storage.flushDeferredIo();
            storage.forceDurable();
            storage.publishDeferredCheckpoint();
            storage.forceDurable();
            assertNotEquals(oldBase, growing.base());
            assertEquals(202, db.query("SELECT * FROM growing").rows().size());
            assertEquals("original", db.query("SELECT payload FROM growing WHERE id = 0").rows().get(0).getString(0));
            if (outcome.equals("CRASH")) {
                db.crash();
            } else {
                db.execute(outcome);
            }
        }
        if (outcome.equals("CRASH")) {
            Database.applyLog(file, dir.resolve("growth.log"));
        }
        try (Database db = Database.open(file, "dba", "dba")) {
            assertEquals(outcome.equals("COMMIT") ? 202 : 1, db.query("SELECT * FROM growing").rows().size());
            assertEquals("original", db.query("SELECT payload FROM growing WHERE id = 0").rows().get(0).getString(0));
            assertEquals("b".repeat(1500), db.query("SELECT payload FROM blocker").rows().get(0).getString(0));
        }
    }

    @Test
    void commitDurantePreparacaoDoCheckpointPreservaUltimoPedido() throws Exception {
        exportWhileCheckpointPending(false);
    }

    @Test
    void commitDepoisDaPreparacaoDoCheckpointPreservaUltimoPedido() throws Exception {
        exportWhileCheckpointPending(true);
    }

    private void exportWhileCheckpointPending(boolean encode) throws Exception {
        Path file = dir.resolve("export.db");
        Database.create(file, DatabaseOptions.defaults());
        try (Database db = Database.open(file, "dba", "dba")) {
            db.checkpointPolicy(CheckpointPolicy.MANUAL);
            db.execute("CREATE TABLE pedido (id_empresa INTEGER, id_pedido INTEGER, PRIMARY KEY (id_empresa, id_pedido))");
            db.execute("CREATE TABLE parcela (id_empresa INTEGER, id_pedido INTEGER, FOREIGN KEY (id_empresa, id_pedido) REFERENCES pedido (id_empresa, id_pedido))");
            db.execute("BEGIN");
            db.execute("INSERT INTO pedido VALUES (99, 6651)");
            List<TableStore> frozen = freeze(db);
            if (encode) {
                frozen.forEach(TableStore::encodePrepared);
            }
            db.execute("INSERT INTO pedido VALUES (99, 6652)");
            db.execute("COMMIT");
            assertEquals(1, db.query("SELECT * FROM pedido WHERE id_empresa = 99 AND id_pedido = 6652").rows().size());
            db.execute("INSERT INTO parcela VALUES (99, 6652)");
            // A worker finishing after commit must not publish the old table image.
            if (!encode) {
                frozen.forEach(TableStore::encodePrepared);
            }
            frozen.forEach(TableStore::abortEncode);
        }
        try (Database db = Database.open(file, "dba", "dba")) {
            assertEquals(2, db.query("SELECT * FROM pedido").rows().size());
            assertEquals(1, db.query("SELECT * FROM pedido WHERE id_empresa = 99 AND id_pedido = 6652").rows().size());
            assertEquals(1, db.query("SELECT * FROM parcela").rows().size());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<TableStore> freeze(Database db) throws Exception {
        Engine engine = engine(db);
        Method method = Engine.class.getDeclaredMethod("freezeDirty");
        method.setAccessible(true);
        return (List<TableStore>) method.invoke(engine);
    }

    private static Engine engine(Database db) throws Exception {
        Field field = Database.class.getDeclaredField("engine");
        field.setAccessible(true);
        return (Engine) field.get(db);
    }
}
