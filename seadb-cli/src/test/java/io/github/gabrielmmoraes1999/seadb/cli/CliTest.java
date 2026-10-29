package io.github.gabrielmmoraes1999.seadb.cli;

import io.github.gabrielmmoraes1999.seadb.Database;
import io.github.gabrielmmoraes1999.seadb.SeaException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertTrue(assertThrows(SeaException.class, () -> Cli.execute(null)).getMessage().contains("create"));
        assertTrue(assertThrows(SeaException.class, () -> Cli.execute(new String[]{"info"})).getMessage().contains("unknown"));
        assertTrue(assertThrows(SeaException.class, () -> Cli.execute(new String[]{"backup"})).getMessage().contains("unknown"));
        assertTrue(assertThrows(SeaException.class, () -> Cli.execute(new String[]{"apply"})).getMessage().contains("unknown"));
        assertTrue(assertThrows(SeaException.class, () -> Cli.execute(new String[]{"export"})).getMessage().contains("unknown"));
        Path ruim = dir.resolve("ruim.db");
        assertThrows(SeaException.class, () -> Cli.execute(new String[]{
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
            assertEquals("cli", db.query("SELECT SEA$DATABASE_NAME FROM SEA$DATABASE").rows().get(0).getString(0));
            assertEquals("ANA", db.session().user());
            assertEquals("via cli", db.query("SELECT SEA$DESCRIPTION FROM SEA$DATABASE").rows().get(0).getString(0));
        }
        Cli.execute(new String[]{"--help"});
    }
}
