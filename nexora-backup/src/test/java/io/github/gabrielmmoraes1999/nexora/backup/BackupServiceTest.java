package io.github.gabrielmmoraes1999.nexora.backup;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.DatabaseOptions;
import io.github.gabrielmmoraes1999.nexora.NexException;
import io.github.gabrielmmoraes1999.nexora.dbsrv.MotorConfig;
import io.github.gabrielmmoraes1999.nexora.dbsrv.MotorServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {

    @TempDir
    Path dir;

    @Test
    void backupCompletoEIncremental() throws Exception {
        assertTrue(assertThrows(NexException.class, () ->
                BackupService.backup("  ", null, null, null, false, dir, null)).getMessage().contains(".db"));
        assertTrue(assertThrows(NexException.class, () ->
                BackupService.backup("jdbc:nexora:embedded:x.db", "dba", null, null, false, dir, null))
                .getMessage().contains("user and password"));
        Path arquivo = dir.resolve("cli.db");
        Database.create(arquivo, new DatabaseOptions(4096, "DBA", "abc", "cli", "Nexora", "UTF8"));
        try (Database db = Database.open(arquivo, "dba", "abc")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'antes')");
        }
        Path copia = dir.resolve("copia");
        List<Integer> progresso = new ArrayList<>();
        String completo = BackupService.backup(arquivo.toString(), null, null, null, false, copia, progresso::add);
        assertEquals(List.of(0, 100), progresso);
        assertTrue(completo.startsWith("full backup written to:"));
        try (Database db = Database.open(arquivo, "dba", "abc")) {
            db.execute("INSERT INTO t VALUES (2, 'depois')");
            db.execute("INSERT INTO t VALUES (?, ?)", 3, "d'agua");
        }
        long vivo = Files.size(dir.resolve("cli.log"));
        Path mods = dir.resolve("mods");
        String incremental = BackupService.backup(arquivo.toString(), null, null, null, true, mods, null);
        assertTrue(incremental.startsWith("incremental backup written to:"));
        assertEquals(vivo, Files.size(dir.resolve("cli.log")));
        assertEquals(vivo, Files.size(mods.resolve("cli.log")));
        assertFalse(Files.exists(mods.resolve("cli.db")));
        try (Database db = Database.open(copia.resolve("cli.db"), "dba", "abc")) {
            assertEquals("antes", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
            assertEquals(0, db.query("SELECT COUNT(*) AS total FROM t WHERE id = 2").rows().get(0).getInt("total"));
        }
    }

    @Test
    void backupPelaConexao() throws Exception {
        Class.forName("io.github.gabrielmmoraes1999.nexora.jdbc.NexDriver");
        Path arquivo = dir.resolve("loja.db");
        Database.create(arquivo, new DatabaseOptions(4096, "DBA", "secret", "loja", "Nexora", "UTF8"));
        try (Database db = Database.open(arquivo, "dba", "secret")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'antes')");
            db.execute("INSERT INTO NEX$USERS (NEX$USER_NAME, NEX$PASSWORD) VALUES ('ana', 'ana')");
        }
        String embedded = "jdbc:nexora:embedded:" + arquivo.toAbsolutePath().toString().replace('\\', '/');
        Path copia = dir.resolve("emb");
        BackupService.backup(embedded, "dba", "secret", "NONE", false, copia, null);
        try (Database db = Database.open(copia.resolve("loja.db"), "dba", "secret")) {
            assertEquals("antes", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
        }
        Path negado = dir.resolve("negado");
        NexException semDba = assertThrows(NexException.class, () ->
                BackupService.backup(embedded, "ana", "ana", null, false, negado, null));
        assertTrue(semDba.getMessage().contains("DBA"));
        assertFalse(Files.exists(negado));

        MotorConfig config = new MotorConfig("127.0.0.1", 0, List.of(new MotorConfig.DatabaseSpec("loja", arquivo)));
        try (MotorServer server = MotorServer.start(config)) {
            String url = "jdbc:nexora://127.0.0.1:" + server.port() + "/loja";
            NexException aberto = assertThrows(NexException.class, () ->
                    BackupService.backup(embedded, "dba", "secret", null, false, dir.resolve("preso"), null));
            assertTrue(aberto.getMessage().contains("already open"));
            try (var connection = DriverManager.getConnection(url, "dba", "secret")) {
                connection.createStatement().execute("INSERT INTO t VALUES (2, 'rede')");
            }
            Path rede = dir.resolve("rede");
            List<Integer> progresso = new ArrayList<>();
            String completo = BackupService.backup(url, "dba", "secret", null, false, rede, progresso::add);
            assertTrue(progresso.contains(100));
            assertTrue(completo.startsWith("full backup written to:"));
            try (var connection = DriverManager.getConnection(url, "dba", "secret")) {
                connection.createStatement().execute("INSERT INTO t VALUES (3, 'depois')");
            }
            Path mods = dir.resolve("mods-rede");
            BackupService.backup(url, "dba", "secret", null, true, mods, null);
            assertFalse(Files.exists(mods.resolve("loja.db")));
            assertTrue(Files.exists(mods.resolve("loja.log")));
            try (Database db = Database.open(rede.resolve("loja.db"), "dba", "secret")) {
                assertEquals("rede", db.query("SELECT nome FROM t WHERE id = 2").rows().get(0).getString("nome"));
                assertEquals(0, db.query("SELECT COUNT(*) AS total FROM t WHERE id = 3").rows().get(0).getInt("total"));
            }
            assertThrows(NexException.class, () ->
                    BackupService.backup(url, "dba", "errada", null, false, dir.resolve("ruim"), null));
        }
    }

    @Test
    void guardaOsCamposSemSenhaAtePedir() throws Exception {
        Path file = dir.resolve("backup.properties");
        BackupSettings settings = new BackupSettings(
                "incremental", "jdbc:nexora://127.0.0.1:4580/loja", "dba", "seg redo",
                false, "NONE", dir.resolve("copia").toString());
        BackupSettings.save(file, settings);
        BackupSettings loaded = BackupSettings.load(file);
        assertEquals("incremental", loaded.operation());
        assertEquals("dba", loaded.user());
        assertEquals("", loaded.password());
        assertFalse(loaded.remember());
        assertEquals(dir.resolve("copia").toString(), loaded.directory());
        BackupSettings.save(file, new BackupSettings(
                settings.operation(), settings.source(), settings.user(), "seg redo", true, settings.role(),
                settings.directory()));
        BackupSettings remembered = BackupSettings.load(file);
        assertTrue(remembered.remember());
        assertEquals("seg redo", remembered.password());
        assertNull(BackupSettings.load(dir.resolve("ausente.properties")));
    }
}
