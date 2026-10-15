package io.github.gabrielmmoraes1999.nexora.cli;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.NexException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
