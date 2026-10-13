package io.github.gabrielmmoraes1999.nexora.studio;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.DatabaseOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioTest {

    @TempDir
    Path dir;

    @Test
    void registroGuardaSenhaSoQuandoPedido() throws Exception {
        Path file = dir.resolve("studio.json");
        assertTrue(Registry.load(file).isEmpty());
        DatabaseEntry entry = new DatabaseEntry("id-1", "Loja", true, "C:\\dados\\loja.db", "localhost", 4580,
                "", "DBA", "a\"b\\c", "vendas", false);
        Registry.save(file, List.of(entry));
        DatabaseEntry loaded = Registry.load(file).get(0);
        assertEquals("id-1", loaded.id());
        assertEquals("C:\\dados\\loja.db", loaded.path());
        assertEquals("", loaded.password());
        assertFalse(loaded.rememberPassword());
        Registry.save(file, List.of(new DatabaseEntry(entry.id(), entry.alias(), false, "", "db.local", 4580,
                "loja", "DBA", "segredo", "", true)));
        DatabaseEntry network = Registry.load(file).get(0);
        assertEquals("segredo", network.password());
        assertEquals("jdbc:nexora://db.local:4580/loja", network.url());
        assertEquals("\"a\"\"b\"", SqlNames.quote("a\"b"));
        assertEquals("a\\_b", SqlNames.likeExact("a_b"));
    }

    @Test
    void ddlECatalogoEnxergamOBanco() throws Exception {
        Path file = dir.resolve("loja.db");
        Database.create(file, new DatabaseOptions(8192, "DBA", "dba", "LOJA", "Nexora", "UTF8"));
        String created = Ddl.create("public", "nota", List.of(
                new Ddl.ColumnDraft("id", "INTEGER", 0, 0, false, true, false, true, ""),
                new Ddl.ColumnDraft("texto", "VARCHAR", 80, 0, true, false, false, false, "")));
        assertTrue(created.contains("\"id\" INTEGER PRIMARY KEY AUTOINCREMENT"));
        assertTrue(created.contains("\"texto\" VARCHAR(80)"));
        try (Connection connection = DriverManager.getConnection("jdbc:nexora:" + file.toAbsolutePath(), "dba", "dba");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE cliente (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        nome VARCHAR(120) NOT NULL,
                        email VARCHAR(120) UNIQUE,
                        ativo BOOLEAN DEFAULT TRUE
                    )
                    """);
            statement.execute("CREATE INDEX idx_nome ON cliente (nome)");
            statement.execute("CREATE SCHEMA vendas");
            statement.execute("CREATE TABLE vendas.pedido (id INTEGER PRIMARY KEY)");
            statement.execute("CREATE VIEW nomes AS SELECT nome FROM cliente");
            statement.execute("CREATE GENERATOR gen_item");
            statement.execute("""
                    CREATE PROCEDURE eco (texto VARCHAR(20))
                    RETURNS (eco VARCHAR(20))
                    AS
                    BEGIN
                      eco = texto;
                      SUSPEND;
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER cliente_bi FOR cliente
                    ACTIVE BEFORE INSERT POSITION 0
                    AS
                    BEGIN
                      IF (NEW.nome IS NULL) THEN
                        NEW.nome = 'sem nome';
                    END
                    """);
            statement.execute(created);
            Outline outline = Outline.load(connection);
            assertTrue(outline.tables().stream().anyMatch(table -> table.name().equalsIgnoreCase("cliente")));
            assertTrue(outline.tables().stream().anyMatch(table -> table.name().equalsIgnoreCase("pedido")
                    && table.schema().equalsIgnoreCase("vendas")));
            assertTrue(outline.views().stream().anyMatch(view -> view.name().equalsIgnoreCase("nomes")));
            assertTrue(outline.procedures().stream().anyMatch(name -> name.equalsIgnoreCase("eco")));
            assertTrue(outline.triggers().stream().anyMatch(trigger -> trigger.name().equalsIgnoreCase("cliente_bi")));
            assertTrue(outline.generators().stream().anyMatch(generator -> generator.name().equalsIgnoreCase("gen_item")));
            assertTrue(outline.schemas().get(0).equalsIgnoreCase("public"));
            assertTrue(outline.schemas().stream().anyMatch(schema -> schema.equalsIgnoreCase("vendas")));
            List<Ddl.ColumnInfo> columns = Outline.columns(connection, "public", "cliente");
            assertEquals(1, columns.get(0).primaryKeyPosition());
            assertTrue(columns.get(0).autoIncrement());
            String ddl = Ddl.table("public", "cliente", columns, Outline.indexes(connection, "public", "cliente"));
            assertTrue(ddl.contains("PRIMARY KEY"));
            assertTrue(ddl.contains("idx_nome"));
            assertTrue(ddl.contains("ativo") && ddl.toUpperCase().contains("TRUE"));
            Outline.Routine routine = Outline.procedure(connection, "eco");
            String procedure = Ddl.procedure(routine.name(), routine.parameters(), routine.source());
            assertTrue(procedure.contains("RETURNS"));
            assertTrue(procedure.contains("SUSPEND"));
            assertNull(ValuesText.parse("", "INTEGER", true));
            assertEquals(12L, ValuesText.parse("12", "INTEGER", false));
            assertEquals("", ValuesText.parse("''", "VARCHAR", true));
        }
    }
}
