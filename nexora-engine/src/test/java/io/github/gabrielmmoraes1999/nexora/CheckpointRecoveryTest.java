package io.github.gabrielmmoraes1999.nexora;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkpoints store pages of open transactions without committing them.
 * Recovery is checked by closing the files and opening the database again.
 */
class CheckpointRecoveryTest {

    @TempDir
    Path dir;

    @Test
    void muitosInsertsSemCommitSaoDesfeitos() {
        Path file = database();
        try (Database db = open(file)) {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            db.execute("INSERT INTO item VALUES (1, 'fixo')");
            db.execute("BEGIN");
            for (int i = 0; i < 3000; i++) {
                db.execute("INSERT INTO item VALUES (?, ?)", i + 2, "lote-" + i);
            }
            db.checkpoint();
            db.checkpoint();
            assertTrue(db.transactionActive());
            assertEquals(3001L, count(db, "item"));
            db.execute("ROLLBACK");
            assertEquals(1L, count(db, "item"));
            assertEquals("fixo", db.query("SELECT nome FROM item").rows().get(0).getString("nome"));
        }
        try (Database db = open(file)) {
            assertEquals(1L, count(db, "item"));
            assertEquals("fixo", db.query("SELECT nome FROM item WHERE id = 1").rows().get(0).getString("nome"));
        }
    }

    @Test
    void commitAntesDoCheckpointERecuperado() {
        Path file = database();
        try (Database db = open(file)) {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
        }
        Database db = open(file);
        try {
            db.execute("BEGIN");
            db.execute("INSERT INTO item VALUES (1, 'ana')");
            db.execute("INSERT INTO item VALUES (2, 'bia')");
            db.execute("COMMIT");
            db.crash();
        } finally {
            db.close();
        }
        try (Database deNovo = open(file)) {
            assertEquals(2L, count(deNovo, "item"));
            assertEquals("ana", deNovo.query("SELECT nome FROM item WHERE id = 1").rows().get(0).getString("nome"));
            assertEquals("bia", deNovo.query("SELECT nome FROM item WHERE id = 2").rows().get(0).getString("nome"));
        }
    }

    @Test
    void quedaDepoisDoUndoNaoConfirmaATransacao() {
        Path file = database();
        try (Database pronto = open(file)) {
            pronto.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            pronto.execute("INSERT INTO item VALUES (1, 'confirmado')");
        }
        Database db = open(file);
        TransactionLog.crashAfterUndo = true;
        try {
            db.execute("BEGIN");
            db.execute("INSERT INTO item VALUES (2, 'aberto')");
            NexException queda = assertThrows(NexException.class, db::checkpoint);
            assertTrue(queda.getMessage().contains("undo"));
        } finally {
            TransactionLog.crashAfterUndo = false;
            db.close();
        }
        try (Database deNovo = open(file)) {
            assertEquals(1L, count(deNovo, "item"));
            assertEquals("confirmado", deNovo.query("SELECT nome FROM item").rows().get(0).getString("nome"));
        }
    }

    @Test
    void quedaNoMeioDaGravacaoDoCheckpointDesfazATransacao() {
        Path file = database();
        try (Database pronto = open(file)) {
            pronto.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            pronto.execute("INSERT INTO item VALUES (1, 'confirmado')");
        }
        Database db = open(file);
        TransactionLog.crashAfterCheckpointPages = true;
        try {
            db.execute("BEGIN");
            db.execute("INSERT INTO item VALUES (2, 'aberto')");
            db.execute("UPDATE item SET nome = 'trocado' WHERE id = 1");
            NexException queda = assertThrows(NexException.class, db::checkpoint);
            assertTrue(queda.getMessage().contains("checkpoint"));
        } finally {
            TransactionLog.crashAfterCheckpointPages = false;
            db.close();
        }
        try (Database deNovo = open(file)) {
            assertEquals(1L, count(deNovo, "item"));
            assertEquals("confirmado", deNovo.query("SELECT nome FROM item WHERE id = 1").rows().get(0).getString("nome"));
        }
    }

    @Test
    void transacoesConcorrentesNaoSeMisturam() {
        Path file = database();
        try (Database db = open(file)) {
            Session uma = db.session();
            Session outra = db.login("dba", "dba");
            db.execute(uma, "CREATE TABLE a (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            db.execute(uma, "CREATE TABLE b (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            db.execute(uma, "INSERT INTO a VALUES (1, 'ana')");
            db.execute(outra, "INSERT INTO b VALUES (1, 'bia')");
            db.execute(uma, "BEGIN");
            db.execute(outra, "BEGIN");
            db.execute(uma, "INSERT INTO a VALUES (2, 'aberta')");
            db.execute(outra, "UPDATE b SET nome = 'nova' WHERE id = 1");
            db.checkpoint();
            assertTrue(db.transactionActive(uma));
            assertTrue(db.transactionActive(outra));
            db.execute(uma, "COMMIT");
            db.execute(outra, "ROLLBACK");
            assertEquals("aberta", db.query("SELECT nome FROM a WHERE id = 2").rows().get(0).getString("nome"));
            assertEquals("bia", db.query("SELECT nome FROM b WHERE id = 1").rows().get(0).getString("nome"));
        }
        try (Database db = open(file)) {
            assertEquals(2L, count(db, "a"));
            assertEquals("bia", db.query("SELECT nome FROM b WHERE id = 1").rows().get(0).getString("nome"));
        }
    }

    @Test
    void transacaoLongaInterrompidaEDesfeitaNaAbertura() {
        Path file = database();
        Database db = open(file);
        try {
            Session longa = db.session();
            Session outra = db.login("dba", "dba");
            db.execute(longa, "CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            db.execute(longa, "CREATE TABLE extra (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            db.execute(longa, "BEGIN");
            for (int i = 0; i < 500; i++) {
                db.execute(longa, "INSERT INTO item VALUES (?, ?)", i + 1, "longo-" + i);
            }
            db.checkpoint();
            db.execute(outra, "INSERT INTO extra VALUES (1, 'salvo')");
            for (int i = 500; i < 1000; i++) {
                db.execute(longa, "INSERT INTO item VALUES (?, ?)", i + 1, "longo-" + i);
            }
            db.checkpoint();
            assertTrue(db.transactionActive(longa));
            db.crash();
        } finally {
            db.close();
        }
        try (Database deNovo = open(file)) {
            assertEquals(0L, count(deNovo, "item"));
            assertEquals("salvo", deNovo.query("SELECT nome FROM extra").rows().get(0).getString("nome"));
        }
    }

    @Test
    void registroIncompletoNoFimDoLogNaoApagaCommitAnterior() throws Exception {
        Path file = database();
        try (Database db = open(file)) {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            db.execute("INSERT INTO item VALUES (1, 'ana')");
        }
        Database db = open(file);
        TransactionLog.crashAfterRedo = true;
        try {
            assertThrows(NexException.class, () -> db.execute("INSERT INTO item VALUES (2, 'bia')"));
        } finally {
            TransactionLog.crashAfterRedo = false;
            db.close();
        }
        Files.write(logOf(file), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, StandardOpenOption.APPEND);
        try (Database deNovo = open(file)) {
            assertEquals("ana", deNovo.query("SELECT nome FROM item WHERE id = 1").rows().get(0).getString("nome"));
            assertEquals("bia", deNovo.query("SELECT nome FROM item WHERE id = 2").rows().get(0).getString("nome"));
        }
    }

    @Test
    void tabelaEIndiceSeguemIguaisDepoisDaRecuperacao() {
        Path file = database();
        Database db = open(file);
        try {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            db.execute("CREATE UNIQUE INDEX idx_nome ON item (nome)");
            db.execute("INSERT INTO item VALUES (1, 'ana')");
            db.execute("BEGIN");
            db.execute("INSERT INTO item VALUES (2, 'bia')");
            db.execute("UPDATE item SET nome = 'nova' WHERE id = 1");
            db.execute("INSERT INTO item VALUES (3, 'caio')");
            db.execute("DELETE FROM item WHERE id = 3");
            db.checkpoint();
            db.crash();
        } finally {
            db.close();
        }
        try (Database deNovo = open(file)) {
            assertEquals(1L, count(deNovo, "item"));
            assertEquals(1, deNovo.query("SELECT id FROM item WHERE nome = 'ana'").size());
            assertEquals(0, deNovo.query("SELECT id FROM item WHERE nome = 'nova'").size());
            assertEquals(0, deNovo.query("SELECT id FROM item WHERE nome = 'bia'").size());
            assertEquals("ana", deNovo.query("SELECT nome FROM item WHERE id = 1").rows().get(0).getString("nome"));
            assertThrows(NexException.class, () -> deNovo.execute("INSERT INTO item VALUES (4, 'ana')"));
            deNovo.execute("INSERT INTO item VALUES (4, 'bia')");
            assertEquals(1, deNovo.query("SELECT id FROM item WHERE nome = 'bia'").size());
        }
    }

    @Test
    void commitGravaPaginasECheckpointAntesDeFechar() throws Exception {
        Path file = database();
        Database db = open(file);
        try {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, foto BLOB)");
            long afterCreate = Files.size(file);
            byte[] photo = new byte[100_000];
            photo[0] = 7;
            photo[photo.length - 1] = 9;
            db.execute("BEGIN");
            db.execute("INSERT INTO item VALUES (1, ?)", photo);
            db.execute("COMMIT");
            assertEquals(db.logLength(), db.checkpointLsn());
            assertTrue(Files.size(file) > afterCreate);
            db.crash();
        } finally {
            db.close();
        }
        try (Database again = open(file)) {
            byte[] stored = again.query("SELECT foto FROM item WHERE id = 1").rows().get(0).getBytes("foto");
            assertArrayEquals(new byte[]{7}, new byte[]{stored[0]});
            assertEquals(9, stored[stored.length - 1] & 0xFF);
            assertEquals(100_000, stored.length);
        }
    }

    @Test
    void commitSemFecharNaoRecriaTabela() {
        Path file = database();
        Database db = open(file);
        try {
            db.execute("BEGIN");
            db.execute("CREATE TABLE negociacao_contas (id INTEGER PRIMARY KEY)");
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY)");
            db.execute("COMMIT");
            db.crash();
        } finally {
            db.close();
        }
        try (Database again = open(file)) {
            again.execute("INSERT INTO negociacao_contas VALUES (1)");
            again.execute("INSERT INTO item VALUES (1)");
            assertEquals(1L, count(again, "negociacao_contas"));
            assertEquals(1L, count(again, "item"));
        }
    }

    @Test
    void insertAntesDeCriarTabelaSobreviveAoCommitSemFechar() {
        Path file = database();
        Database db = open(file);
        try {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            db.execute("BEGIN");
            db.execute("INSERT INTO item VALUES (1, 'ana')");
            db.execute("CREATE TABLE negociacao_contas (id INTEGER PRIMARY KEY)");
            db.execute("COMMIT");
            db.crash();
        } finally {
            db.close();
        }
        try (Database again = open(file)) {
            assertEquals("ana", again.query("SELECT nome FROM item WHERE id = 1").rows().get(0).getString("nome"));
            again.execute("INSERT INTO negociacao_contas VALUES (1)");
            assertEquals(1L, count(again, "negociacao_contas"));
        }
    }

    @Test
    void paginasDaTransacaoVaoParaOArquivoAntesDoCommit() throws Exception {
        Path file = database();
        long initial = Files.size(file);
        Database db = open(file);
        db.checkpointPolicy(new CheckpointPolicy(0, Integer.MAX_VALUE, Long.MAX_VALUE, 1024, 2));
        byte[] photo = new byte[50_000];
        try {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, foto BLOB)");
            db.execute("BEGIN");
            for (int id = 1; id <= 40; id++) {
                db.execute("INSERT INTO item VALUES (?, ?)", id, photo);
            }
            long grown = Files.size(file);
            assertTrue(grown > initial + 1_000_000, "database file grew to " + grown);
            db.execute("COMMIT");
            db.crash();
        } finally {
            db.close();
        }
        try (Database again = open(file)) {
            assertEquals(40L, count(again, "item"));
        }
    }

    @Test
    void commitRetornadoSobreviveAQuedaImediata() {
        Path file = database();
        Database db = open(file);
        try {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, nome VARCHAR(40))");
            db.execute("BEGIN");
            db.execute("INSERT INTO item VALUES (1, 'ana')");
            db.execute("COMMIT");
            db.crash();
        } finally {
            db.close();
        }
        try (Database deNovo = open(file)) {
            assertEquals("ana", deNovo.query("SELECT nome FROM item WHERE id = 1").rows().get(0).getString("nome"));
        }
    }

    private Path database() {
        Path file = dir.resolve("loja.db");
        Database.create(file, DatabaseOptions.defaults());
        return file;
    }

    private static Database open(Path file) {
        Database db = Database.open(file, "dba", "dba");
        db.checkpointPolicy(CheckpointPolicy.MANUAL);
        return db;
    }

    private static long count(Database db, String table) {
        return db.query("SELECT COUNT(*) FROM " + table).rows().get(0).getLong(0);
    }

    private static Path logOf(Path database) {
        String name = database.getFileName().toString();
        return database.resolveSibling(name.substring(0, name.length() - 3) + ".log");
    }
}
