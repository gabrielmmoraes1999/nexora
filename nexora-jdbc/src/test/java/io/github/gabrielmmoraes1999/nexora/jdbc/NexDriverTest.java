package io.github.gabrielmmoraes1999.nexora.jdbc;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.DatabaseOptions;
import io.github.gabrielmmoraes1999.nexora.NexException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexDriverTest {

    @TempDir
    Path dir;

    private String url() {
        return "jdbc:nexora:" + dir.resolve("loja.db").toAbsolutePath();
    }

    private void criarBanco() {
        Database.create(dir.resolve("loja.db"), DatabaseOptions.defaults());
    }

    @Test
    void urlEClasseDoDriver() throws Exception {
        Class.forName("io.github.gabrielmmoraes1999.nexora.jdbc.NexDriver");
        NexDriver driver = new NexDriver();
        assertTrue(driver.acceptsURL("jdbc:nexora:C:/dados/loja.db"));
        assertTrue(driver.acceptsURL("jdbc:nexora:embedded:C:/dados/loja.db"));
        assertTrue(driver.acceptsURL("jdbc:nexora://127.0.0.1:4580/loja"));
        assertFalse(driver.acceptsURL("jdbc:unsupported:dados"));
        assertEquals("C:/dados/loja.db", NexDriver.file("jdbc:nexora://C:/dados/loja.db"));
        assertEquals("C:/dados/loja.db", NexDriver.file("jdbc:nexora:embedded:C:/dados/loja.db"));
        assertEquals("", NexDriver.file("jdbc:nexora://127.0.0.1:4580/loja"));
        NexTarget rede = NexTarget.parse("jdbc:nexora://[::1]:4580/loja");
        assertEquals("::1", rede.host());
        assertEquals(4580, rede.port());
        assertEquals("loja", rede.database());
        assertFalse(rede.embedded());
        assertNull(driver.connect("jdbc:unsupported:dados", null));
        SQLException vazia = assertThrows(SQLException.class, () -> driver.connect("jdbc:nexora:", null));
        assertTrue(vazia.getMessage().contains("file"));
        SQLException semArquivo = assertThrows(SQLException.class, () -> driver.connect("jdbc:nexora:embedded:", null));
        assertTrue(semArquivo.getMessage().contains("file"));
        SQLException semPorta = assertThrows(SQLException.class, () -> driver.connect("jdbc:nexora://localhost/loja", null));
        assertTrue(semPorta.getMessage().contains("port"));
        SQLException semSenha = assertThrows(SQLException.class, () -> driver.connect("jdbc:nexora:C:/dados/loja.db", null));
        assertTrue(semSenha.getMessage().contains("password"));
        DriverPropertyInfo[] properties = driver.getPropertyInfo("jdbc:nexora:C:/dados/loja.db", null);
        assertEquals(3, properties.length);
        assertEquals("roleName", properties[2].name);
        assertFalse(properties[2].required);
    }

    @Test
    void urlEmbeddedAbreOArquivo() throws Exception {
        criarBanco();
        String url = "jdbc:nexora:embedded:" + dir.resolve("loja.db").toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url, "dba", "dba")) {
            connection.createStatement().execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            assertEquals(1, connection.createStatement().executeUpdate("INSERT INTO t VALUES (1)"));
        }
    }

    @Test
    void conexaoSomenteLeituraAceitaWith() throws Exception {
        criarBanco();
        try (Connection connection = DriverManager.getConnection(url(), "dba", "dba")) {
            connection.createStatement().execute("CREATE TABLE t (id INTEGER)");
            connection.setReadOnly(true);
            try (ResultSet rows = connection.createStatement().executeQuery("""
                    WITH n AS (SELECT 1 AS id)
                    SELECT id FROM n
                    """)) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            SQLException escrita = assertThrows(SQLException.class,
                    () -> connection.createStatement().execute("INSERT INTO t (id) VALUES (1)"));
            assertEquals("25006", escrita.getSQLState());
        }
    }

    @Test
    void consultaMetadadosEParametro() throws Exception {
        criarBanco();
        String url = url();
        byte[] foto = new byte[]{1, 2, 3};
        try (Connection connection = DriverManager.getConnection(url, "dba", "dba")) {
            assertEquals("Nexora", connection.getMetaData().getDatabaseProductName());
            assertEquals("DBA", connection.getMetaData().getUserName());
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE cliente (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            nome VARCHAR(40) NOT NULL,
                            foto BLOB
                        )
                        """);
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cliente (nome, foto) VALUES (?, ?)")) {
                insert.setString(1, "Ana");
                insert.setBytes(2, foto);
                assertEquals(1, insert.executeUpdate());
            }
            try (Statement statement = connection.createStatement()) {
                statement.setMaxRows(1);
                try (ResultSet rows = statement.executeQuery("SELECT id, nome, foto FROM cliente")) {
                    assertTrue(rows.next());
                    assertEquals(1L, rows.getLong("id"));
                    assertEquals("Ana", rows.getString("nome"));
                    assertArrayEquals(foto, rows.getBytes("foto"));
                    assertEquals("INTEGER", rows.getMetaData().getColumnTypeName(1));
                    assertEquals("public", rows.getMetaData().getSchemaName(1));
                    assertEquals("cliente", rows.getMetaData().getTableName(1));
                    assertEquals(Types.INTEGER, rows.getMetaData().getColumnType(1));
                    assertFalse(rows.next());
                }
            }
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet tables = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                assertTrue(tables.next());
                assertEquals("cliente", tables.getString("TABLE_NAME"));
                assertEquals("public", tables.getString("TABLE_SCHEM"));
                assertEquals("TABLE", tables.getString("TABLE_TYPE"));
                assertFalse(tables.next());
            }
            assertEquals("public", connection.getSchema());
            assertTrue(meta.getTables(null, "PUBLIC", "cliente", new String[]{"TABLE"}).next());
            assertFalse(meta.getTables(null, "outro", "%", null).next());
            try (ResultSet schemas = meta.getSchemas()) {
                assertTrue(schemas.next());
                assertEquals("public", schemas.getString("TABLE_SCHEM"));
            }
            try (ResultSet columns = meta.getColumns(null, null, "cliente", "%")) {
                assertTrue(columns.next());
                assertEquals("id", columns.getString("COLUMN_NAME"));
                assertEquals("INTEGER", columns.getString("TYPE_NAME"));
                assertEquals("NO", columns.getString("IS_NULLABLE"));
                assertEquals("YES", columns.getString("IS_AUTOINCREMENT"));
                assertTrue(columns.next());
                assertEquals("nome", columns.getString("COLUMN_NAME"));
                assertEquals(40, columns.getInt("COLUMN_SIZE"));
                assertEquals("NO", columns.getString("IS_NULLABLE"));
            }
            try (ResultSet keys = meta.getPrimaryKeys(null, null, "cliente")) {
                assertTrue(keys.next());
                assertEquals("id", keys.getString("COLUMN_NAME"));
                assertEquals(1, keys.getShort("KEY_SEQ"));
                assertEquals("PK_cliente", keys.getString("PK_NAME"));
            }
            try (Connection segunda = DriverManager.getConnection(url, "dba", "dba");
                 ResultSet nomes = segunda.createStatement().executeQuery("SELECT nome FROM cliente")) {
                assertTrue(nomes.next());
                assertEquals("Ana", nomes.getString(1));
            }
            assertThrows(NexException.class, () -> Database.open(dir.resolve("loja.db"), "dba", "dba"));
        }
        try (Connection connection = DriverManager.getConnection(url, "dba", "dba");
             ResultSet rows = connection.createStatement().executeQuery("SELECT nome FROM cliente")) {
            assertTrue(rows.next());
            assertEquals("Ana", rows.getString(1));
        }
    }

    @Test
    void variasConexoesCompartilhamOBanco() throws Exception {
        criarBanco();
        String url = url();
        try (Connection primeira = DriverManager.getConnection(url, "dba", "dba");
             Connection segunda = DriverManager.getConnection(url, "dba", "dba")) {
            primeira.createStatement().execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            assertEquals(1, segunda.createStatement().executeUpdate("INSERT INTO t VALUES (1, 'ana')"));
            primeira.setAutoCommit(false);
            assertEquals(1, primeira.createStatement().executeUpdate("INSERT INTO t VALUES (2, 'bia')"));
            assertEquals(1, segunda.createStatement().executeUpdate("INSERT INTO t VALUES (3, 'caio')"));
            try (ResultSet linhas = segunda.createStatement().executeQuery("SELECT nome FROM t WHERE id = 2")) {
                assertFalse(linhas.next());
            }
            primeira.commit();
            try (ResultSet linhas = segunda.createStatement().executeQuery("SELECT COUNT(*) FROM t")) {
                assertTrue(linhas.next());
                assertEquals(3, linhas.getInt(1));
            }
        }
        try (Connection reaberta = DriverManager.getConnection(url, "dba", "dba");
             ResultSet linhas = reaberta.createStatement().executeQuery("SELECT nome FROM t ORDER BY id")) {
            assertTrue(linhas.next());
            assertEquals("ana", linhas.getString(1));
            assertTrue(linhas.next());
            assertEquals("bia", linhas.getString(1));
            assertTrue(linhas.next());
            assertEquals("caio", linhas.getString(1));
            assertFalse(linhas.next());
        }
    }

    @Test
    void rollbackDesfazInsert() throws Exception {
        criarBanco();
        String url = url();
        try (Connection connection = DriverManager.getConnection(url, "dba", "dba")) {
            connection.createStatement().execute("CREATE TABLE t (id INTEGER PRIMARY KEY, nome VARCHAR(20))");
            connection.setAutoCommit(false);
            assertEquals(1, connection.createStatement().executeUpdate("INSERT INTO t VALUES (1, 'ana')"));
            connection.rollback();
            try (ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM t")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            connection.createStatement().executeUpdate("INSERT INTO t VALUES (2, 'bia')");
            connection.commit();
        }
        try (Connection connection = DriverManager.getConnection(url, "dba", "dba");
             ResultSet rows = connection.createStatement().executeQuery("SELECT nome FROM t")) {
            assertTrue(rows.next());
            assertEquals("bia", rows.getString(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void arquivoAusenteNaoECriado() throws Exception {
        Path arquivo = dir.resolve("loja.db");
        SQLException ausente = assertThrows(SQLException.class, () -> DriverManager.getConnection(url(), "dba", "dba"));
        assertTrue(ausente.getMessage().contains("does not exist"));
        assertFalse(Files.exists(arquivo));
    }

    @Test
    void metadadosDeProcedure() throws Exception {
        criarBanco();
        try (Connection connection = DriverManager.getConnection(url(), "dba", "dba")) {
            connection.createStatement().execute("""
                    CREATE PROCEDURE serie (ate INTEGER)
                    RETURNS (n INTEGER)
                    AS
                    BEGIN
                      n = ate;
                      SUSPEND;
                    END
                    """);
            connection.createStatement().execute("""
                    CREATE PROCEDURE nada
                    AS
                    BEGIN
                      EXIT;
                    END
                    """);
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet procedures = meta.getProcedures(null, "public", "%")) {
                assertTrue(procedures.next());
                assertEquals("public", procedures.getString("PROCEDURE_SCHEM"));
                assertEquals("nada", procedures.getString("PROCEDURE_NAME"));
                assertEquals(DatabaseMetaData.procedureNoResult, procedures.getShort("PROCEDURE_TYPE"));
                assertTrue(procedures.next());
                assertEquals("serie", procedures.getString("PROCEDURE_NAME"));
                assertEquals(DatabaseMetaData.procedureReturnsResult, procedures.getShort("PROCEDURE_TYPE"));
                assertFalse(procedures.next());
            }
            assertFalse(meta.getProcedures(null, "outro", "%").next());
            connection.createStatement().execute("CREATE SCHEMA vendas");
            assertFalse(meta.getProcedures(null, "vendas", "serie").next());
            connection.createStatement().execute("""
                    CREATE PROCEDURE vendas.serie (ate INTEGER)
                    RETURNS (n INTEGER)
                    AS
                    BEGIN
                      n = ate;
                      SUSPEND;
                    END
                    """);
            try (ResultSet vendas = meta.getProcedures(null, "vendas", "serie")) {
                assertTrue(vendas.next());
                assertEquals("vendas", vendas.getString("PROCEDURE_SCHEM"));
                assertEquals("serie", vendas.getString("PROCEDURE_NAME"));
                assertFalse(vendas.next());
            }
            try (ResultSet columns = meta.getProcedureColumns(null, "PUBLIC", "serie", "%")) {
                assertTrue(columns.next());
                assertEquals("public", columns.getString("PROCEDURE_SCHEM"));
                assertEquals("ate", columns.getString("COLUMN_NAME"));
                assertEquals(DatabaseMetaData.procedureColumnIn, columns.getShort("COLUMN_TYPE"));
                assertEquals(Types.INTEGER, columns.getInt("DATA_TYPE"));
                assertEquals(1, columns.getInt("ORDINAL_POSITION"));
                assertTrue(columns.next());
                assertEquals("n", columns.getString("COLUMN_NAME"));
                assertEquals(DatabaseMetaData.procedureColumnOut, columns.getShort("COLUMN_TYPE"));
                assertEquals(2, columns.getInt("ORDINAL_POSITION"));
                assertFalse(columns.next());
            }
        }
    }

    @Test
    void metadataInformaRegraDaChaveEstrangeira() throws Exception {
        criarBanco();
        try (Connection connection = DriverManager.getConnection(url(), "dba", "dba")) {
            Statement statement = connection.createStatement();
            statement.execute("CREATE TABLE pedido (id INTEGER PRIMARY KEY)");
            statement.execute("""
                    CREATE TABLE item (
                        id INTEGER PRIMARY KEY,
                        pedido_id INTEGER,
                        CONSTRAINT FK_item FOREIGN KEY (pedido_id) REFERENCES pedido (id)
                            ON UPDATE CASCADE ON DELETE SET NULL
                    )
                    """);
            try (ResultSet keys = connection.getMetaData().getImportedKeys(null, "public", "item")) {
                assertTrue(keys.next());
                assertEquals("FK_item", keys.getString("FK_NAME"));
                assertEquals(DatabaseMetaData.importedKeyCascade, keys.getShort("UPDATE_RULE"));
                assertEquals(DatabaseMetaData.importedKeySetNull, keys.getShort("DELETE_RULE"));
                assertFalse(keys.next());
            }
        }
    }
}
