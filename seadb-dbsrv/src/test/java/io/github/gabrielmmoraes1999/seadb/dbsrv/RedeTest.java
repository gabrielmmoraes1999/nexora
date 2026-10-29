package io.github.gabrielmmoraes1999.seadb.dbsrv;

import io.github.gabrielmmoraes1999.seadb.Database;
import io.github.gabrielmmoraes1999.seadb.DatabaseOptions;
import io.github.gabrielmmoraes1999.seadb.SeaException;
import io.github.gabrielmmoraes1999.seadb.dbsrv.MotorConfig;
import io.github.gabrielmmoraes1999.seadb.dbsrv.MotorServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedeTest {

    @TempDir
    Path dir;

    @Test
    void jdbcNaRedeGravaELe() throws Exception {
        Path ausente = dir.resolve("ausente.db");
        SeaException falta = assertThrows(SeaException.class, () -> MotorServer.start(
                new MotorConfig("127.0.0.1", 0, List.of(new MotorConfig.DatabaseSpec("loja", ausente)))));
        assertTrue(falta.getMessage().contains("does not exist"));
        assertFalse(Files.exists(ausente));

        Path file = dir.resolve("loja.db");
        Database.create(file, DatabaseOptions.defaults());
        MotorConfig config = new MotorConfig("127.0.0.1", 0, List.of(new MotorConfig.DatabaseSpec("loja", file)));
        byte[] foto = new byte[]{1, 2, 3};
        try (MotorServer server = MotorServer.start(config)) {
            String url = "jdbc:seadb://127.0.0.1:" + server.port() + "/LOJA";
            SQLException nome = assertThrows(SQLException.class,
                    () -> DriverManager.getConnection("jdbc:seadb://127.0.0.1:" + server.port() + "/outra", "dba", "dba"));
            assertTrue(nome.getMessage().contains("database"));
            SQLException senha = assertThrows(SQLException.class,
                    () -> DriverManager.getConnection(url, "dba", "errada"));
            assertTrue(senha.getMessage().contains("password"));
            assertThrows(SeaException.class, () -> Database.open(file, "dba", "dba"));

            try (Connection connection = DriverManager.getConnection(url, "dba", "dba")) {
                assertEquals("DBA", connection.getMetaData().getUserName());
                assertEquals("public", connection.getSchema());
                connection.createStatement().execute("""
                        CREATE TABLE cliente (
                            id INTEGER PRIMARY KEY,
                            nome VARCHAR(40) NOT NULL,
                            foto BLOB,
                            nascimento DATE,
                            total DECIMAL(12, 2)
                        )
                        """);
                connection.createStatement().execute("CREATE SCHEMA vendas");
                connection.setSchema("vendas");
                assertEquals("vendas", connection.getSchema());
                connection.setSchema("public");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO cliente (id, nome, foto, nascimento, total) VALUES (?, ?, ?, ?, ?)")) {
                    insert.setInt(1, 1);
                    insert.setString(2, "Ana");
                    insert.setBytes(3, foto);
                    insert.setDate(4, Date.valueOf("1990-04-12"));
                    insert.setBigDecimal(5, new BigDecimal("12.50"));
                    assertEquals(1, insert.executeUpdate());
                }
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT nome, foto, nascimento, total FROM cliente WHERE id = ?")) {
                    query.setInt(1, 1);
                    try (ResultSet rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("Ana", rows.getString("nome"));
                        assertArrayEquals(foto, rows.getBytes("foto"));
                        assertEquals(Date.valueOf("1990-04-12"), rows.getDate("nascimento"));
                        assertEquals(0, new BigDecimal("12.50").compareTo(rows.getBigDecimal("total")));
                        assertEquals("cliente", rows.getMetaData().getTableName(1));
                        assertFalse(rows.next());
                    }
                }
                try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                    assertTrue(tables.next());
                    assertEquals("cliente", tables.getString("TABLE_NAME"));
                    assertEquals("public", tables.getString("TABLE_SCHEM"));
                }
                try (ResultSet columns = connection.getMetaData().getColumns(null, null, "cliente", "nome")) {
                    assertTrue(columns.next());
                    assertEquals("nome", columns.getString("COLUMN_NAME"));
                    assertEquals(40, columns.getInt("COLUMN_SIZE"));
                }
                try (ResultSet links = connection.createStatement().executeQuery("""
                        SELECT SEA$REMOTE_PROTOCOL, SEA$REMOTE_ADDRESS, SEA$REMOTE_HOST, SEA$REMOTE_OS_USER
                        FROM SEA$CONNECTIONS
                        """)) {
                    assertTrue(links.next());
                    assertEquals("TCPv4", links.getString("SEA$REMOTE_PROTOCOL"));
                    assertEquals("127.0.0.1", links.getString("SEA$REMOTE_ADDRESS"));
                    assertEquals(System.getProperty("user.name"), links.getString("SEA$REMOTE_OS_USER"));
                    assertFalse(links.next());
                }
                try (ResultSet schemas = connection.getMetaData().getSchemas()) {
                    assertTrue(schemas.next());
                    assertEquals("public", schemas.getString("TABLE_SCHEM"));
                    assertTrue(schemas.next());
                    assertEquals("vendas", schemas.getString("TABLE_SCHEM"));
                }
            }

            try (Connection primeira = DriverManager.getConnection(url, "dba", "dba");
                 Connection segunda = DriverManager.getConnection(url, "dba", "dba")) {
                assertEquals(1, segunda.createStatement().executeUpdate("INSERT INTO cliente (id, nome) VALUES (2, 'Bia')"));
                primeira.setAutoCommit(false);
                assertEquals(1, primeira.createStatement().executeUpdate("INSERT INTO cliente (id, nome) VALUES (3, 'Caio')"));
                assertEquals(1, segunda.createStatement().executeUpdate("INSERT INTO cliente (id, nome) VALUES (4, 'Duda')"));
                try (ResultSet linhas = segunda.createStatement().executeQuery("SELECT nome FROM cliente WHERE id = 3")) {
                    assertFalse(linhas.next());
                }
                primeira.commit();
                try (ResultSet linhas = segunda.createStatement().executeQuery("SELECT COUNT(*) FROM cliente")) {
                    assertTrue(linhas.next());
                    assertEquals(4, linhas.getLong(1));
                }
                try (ResultSet ativas = segunda.createStatement().executeQuery("SELECT COUNT(*) FROM SEA$CONNECTIONS")) {
                    assertTrue(ativas.next());
                    assertEquals(2, ativas.getLong(1));
                }
            }
        }

        try (Connection connection = DriverManager.getConnection("jdbc:seadb:embedded:" + file.toAbsolutePath(), "dba", "dba");
             ResultSet rows = connection.createStatement().executeQuery("SELECT nome FROM cliente ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals("Ana", rows.getString(1));
            assertTrue(rows.next());
            assertEquals("Bia", rows.getString(1));
            assertTrue(rows.next());
            assertEquals("Caio", rows.getString(1));
            assertTrue(rows.next());
            assertEquals("Duda", rows.getString(1));
            assertFalse(rows.next());
        }
    }
}
