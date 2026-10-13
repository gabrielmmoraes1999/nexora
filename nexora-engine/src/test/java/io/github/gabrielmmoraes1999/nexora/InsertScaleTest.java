package io.github.gabrielmmoraes1999.nexora;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InsertScaleTest {

    private static final int ROWS = 50_000;

    @TempDir
    Path dir;

    @Test
    void cinquentaMilLinhasComChavePrimaria() {
        Path file = dir.resolve("carga.db");
        Database.create(file, DatabaseOptions.defaults());
        try (Database db = Database.open(file, "dba", "dba")) {
            db.execute("""
                    CREATE TABLE carga (
                        id INTEGER PRIMARY KEY,
                        nome VARCHAR(40) NOT NULL
                    )
                    """);
            db.execute("BEGIN");
            for (int i = 1; i <= ROWS; i++) {
                db.executeUpdate("INSERT INTO carga (id, nome) VALUES (?, ?)", i, "n" + i);
            }
            db.execute("COMMIT");
            assertEquals(ROWS, db.query("SELECT COUNT(*) AS n FROM carga").rows().get(0).getInt("n"));
            assertEquals("n" + (ROWS / 2), db.query("SELECT nome FROM carga WHERE id = ?", ROWS / 2)
                    .rows().get(0).getString("nome"));

            db.execute("BEGIN");
            db.executeUpdate("INSERT INTO carga (id, nome) VALUES (?, ?)", ROWS + 1, "extra");
            db.execute("ROLLBACK");
            assertEquals(ROWS, db.query("SELECT COUNT(*) AS n FROM carga").rows().get(0).getInt("n"));
            assertThrows(NexException.class,
                    () -> db.executeUpdate("INSERT INTO carga (id, nome) VALUES (?, ?)", 1, "dup"));
            assertEquals(ROWS, db.query("SELECT COUNT(*) AS n FROM carga").rows().get(0).getInt("n"));
        }
        try (Database db = Database.open(file, "dba", "dba")) {
            assertEquals(ROWS, db.query("SELECT COUNT(*) AS n FROM carga").rows().get(0).getInt("n"));
            assertEquals("n1", db.query("SELECT nome FROM carga WHERE id = 1").rows().get(0).getString("nome"));
            assertEquals("n" + ROWS, db.query("SELECT nome FROM carga WHERE id = ?", ROWS)
                    .rows().get(0).getString("nome"));
        }
    }
}
