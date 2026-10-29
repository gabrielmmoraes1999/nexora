package io.github.gabrielmmoraes1999.seadb.backup;

import io.github.gabrielmmoraes1999.seadb.Database;
import io.github.gabrielmmoraes1999.seadb.DatabaseOptions;
import io.github.gabrielmmoraes1999.seadb.SeaException;
import io.github.gabrielmmoraes1999.seadb.dbsrv.MotorConfig;
import io.github.gabrielmmoraes1999.seadb.dbsrv.MotorServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
    void insertsAndNewConnectionsContinueWhileRemoteBackupIsOpen() throws Exception {
        Path file = dir.resolve("online.db");
        Database.create(file, DatabaseOptions.defaults());
        try (Database db = Database.open(file, "dba", "dba")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            db.execute("INSERT INTO t VALUES (1)");
        }
        var config = new MotorConfig("127.0.0.1", 0, List.of(new MotorConfig.DatabaseSpec("online", file)));
        try (MotorServer server = MotorServer.start(config)) {
            String url = "jdbc:seadb://127.0.0.1:" + server.port() + "/online";
            var wrote = new java.util.concurrent.atomic.AtomicBoolean();
            BackupService.backup(url, "dba", "dba", null, false, dir.resolve("copy"), percent -> {
                if (percent != 0 || !wrote.compareAndSet(false, true)) return;
                var future = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try (var connection = DriverManager.getConnection(url, "dba", "dba")) {
                        connection.createStatement().execute("INSERT INTO t VALUES (2)");
                        try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM t")) {
                            assertTrue(rows.next());
                            assertEquals(2, rows.getInt(1));
                        }
                    } catch (java.sql.SQLException exception) {
                        throw new RuntimeException(exception);
                    }
                });
                try { future.get(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception exception) { throw new AssertionError("SQL blocked by backup", exception); }
            });
            assertTrue(wrote.get());
            try (Database db = Database.open(dir.resolve("copy/online.db"), "dba", "dba")) {
                assertEquals(1, db.query("SELECT * FROM t").rows().size());
            }
        }
        try (Database db = Database.open(file, "dba", "dba")) {
            assertEquals(2, db.query("SELECT * FROM t").rows().size());
        }
    }

    @Test
    void backupCompletoEIncremental() throws Exception {
        assertTrue(assertThrows(SeaException.class, () ->
                BackupService.backup("  ", null, null, null, false, dir, null)).getMessage().contains(".db"));
        assertTrue(assertThrows(SeaException.class, () ->
                BackupService.backup("jdbc:seadb:embedded:x.db", "dba", null, null, false, dir, null))
                .getMessage().contains("user and password"));
        Path arquivo = dir.resolve("cli.db");
        Database.create(arquivo, new DatabaseOptions(4096, "DBA", "abc", "cli", "SeaDB", "UTF8"));
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
        Class.forName("io.github.gabrielmmoraes1999.seadb.jdbc.SeaDriver");
        Path arquivo = dir.resolve("loja.db");
        Database.create(arquivo, new DatabaseOptions(4096, "DBA", "secret", "loja", "SeaDB", "UTF8"));
        try (Database db = Database.open(arquivo, "dba", "secret")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'antes')");
            db.execute("INSERT INTO SEA$USERS (SEA$USER_NAME, SEA$PASSWORD) VALUES ('ana', 'ana')");
        }
        String embedded = "jdbc:seadb:embedded:" + arquivo.toAbsolutePath().toString().replace('\\', '/');
        Path copia = dir.resolve("emb");
        BackupService.backup(embedded, "dba", "secret", "NONE", false, copia, null);
        try (Database db = Database.open(copia.resolve("loja.db"), "dba", "secret")) {
            assertEquals("antes", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
        }
        Path negado = dir.resolve("negado");
        SeaException semDba = assertThrows(SeaException.class, () ->
                BackupService.backup(embedded, "ana", "ana", null, false, negado, null));
        assertTrue(semDba.getMessage().contains("DBA"));
        assertFalse(Files.exists(negado));

        MotorConfig config = new MotorConfig("127.0.0.1", 0, List.of(new MotorConfig.DatabaseSpec("loja", arquivo)));
        try (MotorServer server = MotorServer.start(config)) {
            String url = "jdbc:seadb://127.0.0.1:" + server.port() + "/loja";
            SeaException aberto = assertThrows(SeaException.class, () ->
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
            assertThrows(SeaException.class, () ->
                    BackupService.backup(url, "dba", "errada", null, false, dir.resolve("ruim"), null));
        }
    }

    @Test
    void backupPelaLinhaDeComando() throws Exception {
        assertTrue(assertThrows(SeaException.class, () -> Backup.execute(null)).getMessage().contains("<file.db>"));
        assertTrue(assertThrows(SeaException.class, () -> Backup.execute(new String[]{"loja.db"}))
                .getMessage().contains("<file.db>"));
        assertTrue(assertThrows(SeaException.class, () -> Backup.execute(new String[]{"--page-size", "4096"}))
                .getMessage().contains("unknown option"));
        ByteArrayOutputStream help = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        System.setOut(new PrintStream(help, true, StandardCharsets.UTF_8));
        try {
            Backup.execute(new String[]{"--help"});
        } finally {
            System.setOut(previous);
        }
        assertTrue(help.toString(StandardCharsets.UTF_8).contains("incremental"));

        Path arquivo = dir.resolve("cli.db");
        Database.create(arquivo, new DatabaseOptions(4096, "DBA", "abc", "cli", "SeaDB", "UTF8"));
        try (Database db = Database.open(arquivo, "dba", "abc")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'antes')");
        }
        Path copia = dir.resolve("copia");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        try {
            Backup.execute(new String[]{"full", arquivo.toString(), copia.toString()});
        } finally {
            System.setOut(previous);
        }
        String saida = console.toString(StandardCharsets.UTF_8);
        assertTrue(saida.contains("backup: 0%"));
        assertTrue(saida.contains("backup: 100%"));
        assertTrue(saida.contains("full backup written to:"));
        try (Database db = Database.open(copia.resolve("cli.db"), "dba", "abc")) {
            assertEquals("antes", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
        }
        try (Database db = Database.open(arquivo, "dba", "abc")) {
            db.execute("INSERT INTO t VALUES (2, 'depois')");
        }
        long vivo = Files.size(dir.resolve("cli.log"));
        Path mods = dir.resolve("mods");
        Backup.execute(new String[]{"incremental", arquivo.toString(), mods.toString()});
        assertEquals(vivo, Files.size(dir.resolve("cli.log")));
        assertEquals(vivo, Files.size(mods.resolve("cli.log")));
        assertFalse(Files.exists(mods.resolve("cli.db")));
        Path direto = dir.resolve("direto");
        Backup.execute(new String[]{arquivo.toString(), direto.toString()});
        assertTrue(Files.exists(direto.resolve("cli.db")));
        assertTrue(assertThrows(SeaException.class, () -> Backup.execute(new String[]{
                "full", arquivo.toString(), copia.toString(), "extra"
        })).getMessage().contains("too many arguments"));
    }

    @Test
    void backupPelaLinhaDeComandoComConexao() throws Exception {
        Class.forName("io.github.gabrielmmoraes1999.seadb.jdbc.SeaDriver");
        Path arquivo = dir.resolve("loja.db");
        Database.create(arquivo, new DatabaseOptions(4096, "DBA", "secret", "loja", "SeaDB", "UTF8"));
        try (Database db = Database.open(arquivo, "dba", "secret")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'antes')");
            db.execute("INSERT INTO SEA$USERS (SEA$USER_NAME, SEA$PASSWORD) VALUES ('ana', 'ana')");
        }
        String embedded = "jdbc:seadb:embedded:" + arquivo.toAbsolutePath().toString().replace('\\', '/');
        Path copia = dir.resolve("emb");
        Backup.execute(new String[]{
                "full", embedded, "--user", "dba", "--password=secret", "--role", "NONE", copia.toString()
        });
        try (Database db = Database.open(copia.resolve("loja.db"), "dba", "secret")) {
            assertEquals("antes", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
        }
        Path negado = dir.resolve("negado");
        SeaException semDba = assertThrows(SeaException.class, () -> Backup.execute(new String[]{
                "full", embedded, "--user", "ana", "--password", "ana", negado.toString()
        }));
        assertTrue(semDba.getMessage().contains("DBA"));
        assertFalse(Files.exists(negado));
        assertTrue(assertThrows(SeaException.class, () -> Backup.execute(new String[]{
                "incremental", "--url", embedded, "--user", "dba", copia.toString()
        })).getMessage().contains("user and password"));

        MotorConfig config = new MotorConfig("127.0.0.1", 0, List.of(new MotorConfig.DatabaseSpec("loja", arquivo)));
        try (MotorServer server = MotorServer.start(config)) {
            String url = "jdbc:seadb://127.0.0.1:" + server.port() + "/loja";
            try (var connection = DriverManager.getConnection(url, "dba", "secret")) {
                connection.createStatement().execute("INSERT INTO t VALUES (2, 'rede')");
            }
            Path rede = dir.resolve("rede");
            ByteArrayOutputStream console = new ByteArrayOutputStream();
            PrintStream previous = System.out;
            System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
            try {
                Backup.execute(new String[]{
                        "full", "--url", url, "--user", "dba", "--password", "secret", rede.toString()
                });
            } finally {
                System.setOut(previous);
            }
            String saida = console.toString(StandardCharsets.UTF_8);
            assertTrue(saida.contains("backup: 100%"));
            assertTrue(saida.contains("full backup written to:"));
            try (var connection = DriverManager.getConnection(url, "dba", "secret")) {
                connection.createStatement().execute("INSERT INTO t VALUES (3, 'depois')");
            }
            Path mods = dir.resolve("mods-rede");
            Backup.execute(new String[]{"incremental", url, "--user", "dba", "--password", "secret", mods.toString()});
            assertFalse(Files.exists(mods.resolve("loja.db")));
            assertTrue(Files.exists(mods.resolve("loja.log")));
            try (Database db = Database.open(rede.resolve("loja.db"), "dba", "secret")) {
                assertEquals("rede", db.query("SELECT nome FROM t WHERE id = 2").rows().get(0).getString("nome"));
                assertEquals(0, db.query("SELECT COUNT(*) AS total FROM t WHERE id = 3").rows().get(0).getInt("total"));
            }
            assertThrows(SeaException.class, () -> Backup.execute(new String[]{
                    "full", url, "--user", "dba", "--password", "errada", dir.resolve("ruim").toString()
            }));
        }
    }

    @Test
    void guardaOsCamposSemSenhaAtePedir() throws Exception {
        Path file = dir.resolve("backup.properties");
        BackupSettings settings = new BackupSettings(
                "incremental", "jdbc:seadb://127.0.0.1:4580/loja", "dba", "seg redo",
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
