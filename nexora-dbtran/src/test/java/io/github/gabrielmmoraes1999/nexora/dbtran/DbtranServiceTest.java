package io.github.gabrielmmoraes1999.nexora.dbtran;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.DatabaseOptions;
import io.github.gabrielmmoraes1999.nexora.NexException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbtranServiceTest {

    @TempDir
    Path dir;

    @Test
    void exportaOLogEOBanco() throws Exception {
        assertTrue(assertThrows(NexException.class, () ->
                DbtranService.export("  ", dir.resolve("vazio.sql"), null)).getMessage().contains(".log"));
        assertTrue(assertThrows(NexException.class, () ->
                DbtranService.export(dir.resolve("loja.log").toString(), null, null)).getMessage().contains(".sql"));
        Path arquivo = dir.resolve("loja.db");
        Database.create(arquivo, new DatabaseOptions(4096, "DBA", "abc", "loja", "Nexora", "UTF8"));
        try (Database db = Database.open(arquivo, "dba", "abc")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (1, 'antes')");
            db.execute("INSERT INTO t VALUES (?, ?)", 2, "d'agua");
        }
        Path sql = dir.resolve("loja.sql");
        List<Integer> progresso = new ArrayList<>();
        String escrito = DbtranService.export(dir.resolve("loja.log").toString(), sql, progresso::add);
        assertEquals(0, progresso.get(0));
        assertEquals(100, progresso.get(progresso.size() - 1));
        assertTrue(escrito.startsWith("sql written to:"));
        String texto = Files.readString(sql);
        assertTrue(texto.contains("CREATE TABLE t"));
        assertTrue(texto.contains("INSERT INTO t VALUES (1, 'antes');"));
        assertTrue(texto.contains("INSERT INTO t VALUES (2, 'd''agua');"));
        Path peloBanco = dir.resolve("pelo-db.sql");
        DbtranService.export(arquivo.toString(), peloBanco, null);
        assertEquals(texto, Files.readString(peloBanco));
        NexException destino = assertThrows(NexException.class, () ->
                DbtranService.export(dir.resolve("loja.log").toString(), dir.resolve("loja.log"), null));
        assertTrue(destino.getMessage().contains("different file"));
    }

    @Test
    void exportaPelaLinhaDeComando() throws Exception {
        assertTrue(assertThrows(NexException.class, () -> Dbtran.execute(new String[]{"loja.log"}))
                .getMessage().contains("<file.log>"));
        ByteArrayOutputStream help = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        System.setOut(new PrintStream(help, true, StandardCharsets.UTF_8));
        try {
            Dbtran.execute(new String[]{"--help"});
        } finally {
            System.setOut(previous);
        }
        assertTrue(help.toString(StandardCharsets.UTF_8).contains("<file.sql>"));

        Path arquivo = dir.resolve("cli.db");
        Database.create(arquivo, new DatabaseOptions(4096, "DBA", "abc", "cli", "Nexora", "UTF8"));
        try (Database db = Database.open(arquivo, "dba", "abc")) {
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            db.execute("INSERT INTO t VALUES (?, ?)", 1, "d'agua");
        }
        Path sql = dir.resolve("cli.sql");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        try {
            Dbtran.execute(new String[]{dir.resolve("cli.log").toString(), sql.toString()});
        } finally {
            System.setOut(previous);
        }
        String saida = console.toString(StandardCharsets.UTF_8);
        assertTrue(saida.contains("exporting: 0%"));
        assertTrue(saida.contains("exporting: 100%"));
        assertTrue(saida.contains("sql written to:"));
        String texto = Files.readString(sql);
        assertTrue(texto.contains("INSERT INTO t VALUES (1, 'd''agua');"));
        Path peloBanco = dir.resolve("pelo-db.sql");
        Dbtran.execute(new String[]{arquivo.toString(), peloBanco.toString()});
        assertEquals(texto, Files.readString(peloBanco));
    }

    @Test
    void guardaOsCampos() throws Exception {
        Path file = dir.resolve("dbtran.properties");
        DbtranSettings settings = new DbtranSettings(
                dir.resolve("loja.log").toString(), dir.resolve("loja.sql").toString());
        DbtranSettings.save(file, settings);
        DbtranSettings loaded = DbtranSettings.load(file);
        assertEquals(settings.source(), loaded.source());
        assertEquals(settings.sql(), loaded.sql());
        assertNull(DbtranSettings.load(dir.resolve("ausente.properties")));
    }
}
