package io.github.gabrielmmoraes1999.nexora.cli;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.NexException;

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
        assertTrue(assertThrows(NexException.class, () -> Cli.execute(null)).getMessage().contains("create"));
        assertTrue(assertThrows(NexException.class, () -> Cli.execute(new String[]{"info"})).getMessage().contains("desconhecido"));
        Path ruim = dir.resolve("ruim.xdb");
        assertThrows(NexException.class, () -> Cli.execute(new String[]{
                "create", ruim.toString(), "--page-size", "2048", "--password", "x"
        }));
        assertFalse(Files.exists(ruim));
        Path arquivo = dir.resolve("cli.xdb");
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
}
