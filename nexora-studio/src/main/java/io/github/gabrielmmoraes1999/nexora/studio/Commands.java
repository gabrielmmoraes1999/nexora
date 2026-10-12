package io.github.gabrielmmoraes1999.nexora.studio;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class Commands {

    private Commands() {
    }

    static void run(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
