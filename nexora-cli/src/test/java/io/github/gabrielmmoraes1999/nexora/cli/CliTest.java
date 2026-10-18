package io.github.gabrielmmoraes1999.nexora.cli;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.DatabaseOptions;
import io.github.gabrielmmoraes1999.nexora.NexException;
import io.github.gabrielmmoraes1999.nexora.dbsrv.MotorConfig;
import io.github.gabrielmmoraes1999.nexora.dbsrv.MotorServer;
import io.github.gabrielmmoraes1999.nexora.net.Wire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    @TempDir
    Path dir;

    @Test
    void createGravaOArquivo() throws Exception {
        assertTrue(assertThrows(NexException.class, () -> Cli.execute(null)).getMessage().contains("create"));
        assertTrue(assertThrows(NexException.class, () -> Cli.execute(new String[]{"info"})).getMessage().contains("unknown"));
        Path ruim = dir.resolve("ruim.db");
        assertThrows(NexException.class, () -> Cli.execute(new String[]{
                "create", ruim.toString(), "--page-size", "2048", "--password", "x"
        }));
        assertFalse(Files.exists(ruim));
        Path arquivo = dir.resolve("cli.db");
        Cli.execute(new String[]{
                "create", arquivo.toString(), "--page-size", "4096", "--user", "ana", "--password", "abc",
                "--charset", "UTF8", "--description", "via cli"
        });
        try (Database db = Database.open(arquivo, "ana", "abc")) {
            assertEquals(4096, db.pageSize());
            assertEquals("cli", db.query("SELECT NEX$DATABASE_NAME FROM NEX$DATABASE").rows().get(0).getString(0));
            assertEquals("ANA", db.session().user());
            assertEquals("via cli", db.query("SELECT NEX$DESCRIPTION FROM NEX$DATABASE").rows().get(0).getString(0));
        }
        Cli.execute(new String[]{"--help"});
    }

    @Test
    void backupEApplyPeloCli() throws Exception {
        Path arquivo = dir.resolve("cli.db");
        Cli.execute(new String[]{"create", arquivo.toString(), "--password", "abc"});
        try (Database db = Database.open(arquivo, "dba", "abc")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'antes')");
        }
        Path copia = dir.resolve("copia");
        Cli.execute(new String[]{"backup", "full", arquivo.toString(), copia.toString()});
        try (Database db = Database.open(arquivo, "dba", "abc")) {
            db.execute("INSERT INTO t VALUES (2, 'depois')");
            db.execute("INSERT INTO t VALUES (?, ?)", 3, "d'agua");
        }
        long vivo = Files.size(dir.resolve("cli.log"));
        Path mods = dir.resolve("mods");
        Cli.execute(new String[]{"backup", "incremental", arquivo.toString(), mods.toString()});
        assertEquals(vivo, Files.size(dir.resolve("cli.log")));
        assertEquals(vivo, Files.size(mods.resolve("cli.log")));
        assertFalse(Files.exists(mods.resolve("cli.db")));
        Path sql = dir.resolve("cli.sql");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        try {
            Cli.execute(new String[]{"export", dir.resolve("cli.log").toString(), sql.toString()});
        } finally {
            System.setOut(previous);
        }
        String saida = console.toString(StandardCharsets.UTF_8);
        assertTrue(saida.contains("exporting: 0%"));
        assertTrue(saida.contains("exporting: 100%"));
        String texto = Files.readString(sql);
        assertTrue(texto.contains("INSERT INTO t VALUES (2, 'depois');"));
        assertTrue(texto.contains("INSERT INTO t VALUES (3, 'd''agua');"));
        Path peloBanco = dir.resolve("pelo-db.sql");
        Cli.execute(new String[]{"export", arquivo.toString(), peloBanco.toString()});
        assertEquals(texto, Files.readString(peloBanco));
        Path anterior = dir.resolve("anterior.sql");
        Cli.execute(new String[]{"export", copia.resolve("cli.log").toString(), anterior.toString()});
        String backup = Files.readString(anterior);
        assertTrue(backup.contains("CREATE TABLE t"));
        assertTrue(backup.contains("INSERT INTO t VALUES (1, 'antes');"));
        Cli.execute(new String[]{"apply", copia.resolve("cli.db").toString(), dir.resolve("cli.log").toString()});
        try (Database db = Database.open(copia.resolve("cli.db"), "dba", "abc")) {
            assertEquals("depois", db.query("SELECT nome FROM t WHERE id = 2").rows().get(0).getString("nome"));
            assertEquals(1, db.query("SELECT NEX$FORMAT FROM NEX$DATABASE").rows().get(0).getInt("NEX$FORMAT"));
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
        Cli.execute(new String[]{"backup", "full", embedded, "--user", "dba", "--password", "secret", copia.toString()});
        try (Database db = Database.open(copia.resolve("loja.db"), "dba", "secret")) {
            assertEquals("antes", db.query("SELECT nome FROM t WHERE id = 1").rows().get(0).getString("nome"));
        }
        Path negado = dir.resolve("negado");
        NexException semDba = assertThrows(NexException.class, () -> Cli.execute(new String[]{
                "backup", "full", embedded, "--user", "ana", "--password", "ana", negado.toString()
        }));
        assertTrue(semDba.getMessage().contains("DBA"));
        assertFalse(Files.exists(negado));
        assertThrows(NexException.class, () -> Cli.execute(new String[]{
                "backup", "incremental", "--url", embedded, "--user", "dba", copia.toString()
        }));

        MotorConfig config = new MotorConfig("127.0.0.1", 0, List.of(new MotorConfig.DatabaseSpec("loja", arquivo)));
        try (MotorServer server = MotorServer.start(config)) {
            String url = "jdbc:nexora://127.0.0.1:" + server.port() + "/loja";
            NexException aberto = assertThrows(NexException.class, () -> Cli.execute(new String[]{
                    "backup", "full", embedded, "--user", "dba", "--password", "secret", dir.resolve("preso").toString()
            }));
            assertTrue(aberto.getMessage().contains("already open"));
            try (var connection = DriverManager.getConnection(url, "dba", "secret")) {
                connection.createStatement().execute("INSERT INTO t VALUES (2, 'rede')");
            }
            interrompeBackup(server.port());
            Path parcial = dir.resolve("parcial");
            Cli.execute(new String[]{"backup", "incremental", url, "--user", "dba", "--password", "secret", parcial.toString()});
            Path sqlParcial = dir.resolve("parcial.sql");
            Cli.execute(new String[]{"export", parcial.resolve("loja.log").toString(), sqlParcial.toString()});
            assertTrue(Files.readString(sqlParcial).contains("'rede'"));
            assertFalse(Files.exists(parcial.resolve("loja.db")));

            Path rede = dir.resolve("rede");
            ByteArrayOutputStream console = new ByteArrayOutputStream();
            PrintStream previous = System.out;
            System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
            try {
                Cli.execute(new String[]{
                        "backup", "full", "--url", url, "--user", "dba", "--password", "secret", rede.toString()
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
            Cli.execute(new String[]{"backup", "incremental", url, "--user", "dba", "--password", "secret", mods.toString()});
            assertFalse(Files.exists(mods.resolve("loja.db")));
            Path sql = dir.resolve("mods-rede.sql");
            Cli.execute(new String[]{"export", mods.resolve("loja.log").toString(), sql.toString()});
            String texto = Files.readString(sql);
            assertTrue(texto.contains("'depois'"));
            try (Database db = Database.open(rede.resolve("loja.db"), "dba", "secret")) {
                assertEquals("rede", db.query("SELECT nome FROM t WHERE id = 2").rows().get(0).getString("nome"));
                assertEquals(0, db.query("SELECT COUNT(*) AS total FROM t WHERE id = 3").rows().get(0).getInt("total"));
            }
            assertThrows(NexException.class, () -> Cli.execute(new String[]{
                    "backup", "full", url, "--user", "dba", "--password", "errada", dir.resolve("ruim").toString()
            }));
        }
    }

    private static void interrompeBackup(int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            Wire.writeFrame(output, Wire.LOGIN, Wire.encodeLogin("loja", "dba", "secret", null));
            assertEquals(Wire.OK, Wire.readFrame(input).opcode());
            Wire.writeFrame(output, Wire.BACKUP, Wire.encodeBackupRequest(false));
            Wire.Frame offer = Wire.readFrame(input);
            assertEquals(Wire.OK, offer.opcode());
            assertFalse(Wire.decodeBackupOffer(offer.body()).files().isEmpty());
            Wire.writeFrame(output, Wire.BACKUP_READ, Wire.encodeBackupRead(0, 0, 64));
            Wire.Frame chunk = Wire.readFrame(input);
            assertEquals(Wire.OK, chunk.opcode());
            assertEquals(64, chunk.body().length);
        }
    }
}
