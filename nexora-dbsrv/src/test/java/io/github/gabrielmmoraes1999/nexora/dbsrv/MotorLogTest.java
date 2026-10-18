package io.github.gabrielmmoraes1999.nexora.dbsrv;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.DatabaseOptions;
import io.github.gabrielmmoraes1999.nexora.NexException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotorLogTest {

    @TempDir
    Path dir;

    @Test
    void consoleRecebeConexaoESql() throws Exception {
        Path file = dir.resolve("loja.db");
        Database.create(file, DatabaseOptions.defaults());
        Path extra = dir.resolve("caixa.db");
        Database.create(extra, DatabaseOptions.defaults());
        Recording log = new Recording();
        MotorConfig config = new MotorConfig("127.0.0.1", 0, List.of(new MotorConfig.DatabaseSpec("loja", file)));
        try (MotorServer server = MotorServer.start(config, log)) {
            assertEquals(List.of("loja"), log.databases);
            assertTrue(log.pageSizes.get(0) >= 4096);
            String url = "jdbc:nexora://127.0.0.1:" + server.port() + "/loja";
            assertThrows(SQLException.class, () -> DriverManager.getConnection(url, "dba", "errada"));
            assertTrue(log.failures.stream().anyMatch(message -> message.contains("Login failed")));
            try (Connection connection = DriverManager.getConnection(url, "dba", "dba")) {
                assertEquals(1, server.connections());
                connection.createStatement().execute("SELECT 1 FROM NEX$DATABASE");
            }
            assertTrue(log.disconnected.await(5, TimeUnit.SECONDS));
            assertTrue(log.statements.stream().anyMatch(sql -> sql.toUpperCase().contains("SELECT")));
            assertEquals("DBA", log.users.get(0));
            server.publish("caixa", extra);
            assertEquals(2, server.databaseCount());
            assertEquals(List.of("loja", "caixa"), log.databases);
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:nexora://127.0.0.1:" + server.port() + "/caixa", "dba", "dba")) {
                assertEquals("DBA", connection.getMetaData().getUserName());
            }
            assertThrows(NexException.class, () -> server.publish("CAIXA", extra));
        }
    }

    private static final class Recording extends MotorLog {
        private final List<String> databases = new ArrayList<>();
        private final List<Integer> pageSizes = new ArrayList<>();
        private final List<String> users = new ArrayList<>();
        private final List<String> statements = Collections.synchronizedList(new ArrayList<>());
        private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch disconnected = new CountDownLatch(1);

        @Override
        public void databaseStarted(String name, Path file, int pageSize) {
            databases.add(name);
            pageSizes.add(pageSize);
        }

        @Override
        public void connected(long id, String database, String user, String address) {
            users.add(user);
            assertTrue(id >= 1);
            assertTrue(address.contains("127.0.0.1") || address.contains("0:0:0:0:0:0:0:1"));
        }

        @Override
        public void disconnected(long id, String database, String user, String address) {
            disconnected.countDown();
        }

        @Override
        public void statement(String database, String user, String sql) {
            statements.add(sql);
        }

        @Override
        public void failed(String message) {
            failures.add(message);
        }
    }
}
