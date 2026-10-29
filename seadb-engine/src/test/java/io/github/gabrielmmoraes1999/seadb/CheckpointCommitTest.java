package io.github.gabrielmmoraes1999.seadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckpointCommitTest {
    @TempDir
    Path dir;

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
        Field field = Database.class.getDeclaredField("engine");
        field.setAccessible(true);
        Engine engine = (Engine) field.get(db);
        Method method = Engine.class.getDeclaredMethod("freezeDirty");
        method.setAccessible(true);
        return (List<TableStore>) method.invoke(engine);
    }
}
