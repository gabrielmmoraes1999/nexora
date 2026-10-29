package io.github.gabrielmmoraes1999.seadb.jdbc;

import io.github.gabrielmmoraes1999.seadb.SeaException;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver for opening a SeaDB file in DBeaver and in {@link DriverManager}.
 *
 * <p>Class: {@code io.github.gabrielmmoraes1999.seadb.jdbc.SeaDriver}<br>
 * Embedded: {@code jdbc:seadb:embedded:C:/data/store.db}<br>
 * Network: {@code jdbc:seadb://192.168.0.10:4580/store}<br>
 * Initial user: {@code dba}<br>
 * Initial password: {@code dba}<br>
 * The {@code .db} file already exists. The SeaDB CLI creates it. Without the {@code .log} beside it, opening writes a new log with only the header, and the old log no longer applies.
 */
public final class SeaDriver implements Driver {

    public static final String URL_PREFIX = "jdbc:seadb:";

    static {
        try {
            DriverManager.registerDriver(new SeaDriver());
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    public SeaDriver() {
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        SeaTarget target;
        try {
            target = SeaTarget.parse(url);
        } catch (SeaException exception) {
            throw new SQLException(exception.getMessage(), "08001", exception);
        }
        if (target.embedded() && target.path().isEmpty()) {
            throw new SQLException("specify the file in the URL jdbc:seadb:<file.db>", "08001");
        }
        String user = property(info, "user");
        String password = property(info, "password");
        if (user == null || user.isBlank() || password == null || password.isEmpty()) {
            throw new SQLException("user and password are required", "28000");
        }
        String role = property(info, "roleName");
        if (role == null || role.isBlank()) {
            role = property(info, "role");
        }
        try {
            if (target.embedded()) {
                SharedDatabase.Opened opened = SharedDatabase.open(target.path(), user, password, role);
                return new SeaConnection(url, new LocalAccess(opened.shared(), opened.session()));
            }
            return new SeaConnection(url, RemoteAccess.connect(
                    target.host(), target.port(), target.database(), user, password, role));
        } catch (SeaException exception) {
            throw Jdbc.wrap(exception);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.regionMatches(true, 0, URL_PREFIX, 0, URL_PREFIX.length());
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        DriverPropertyInfo user = new DriverPropertyInfo("user", property(info, "user"));
        user.required = true;
        user.description = "Database user. The initial DBA is dba.";
        DriverPropertyInfo password = new DriverPropertyInfo("password", property(info, "password"));
        password.required = true;
        password.description = "Database password. The initial DBA password is dba.";
        DriverPropertyInfo role = new DriverPropertyInfo("roleName", property(info, "roleName"));
        role.required = false;
        role.description = "Connection role. Empty or NONE connects without a role.";
        return new DriverPropertyInfo[]{user, password, role};
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw Jdbc.unsupported("java.util.logging");
    }

    static String file(String url) {
        SeaTarget target = SeaTarget.parse(url);
        return target.embedded() ? target.path() : "";
    }

    private static String property(Properties info, String name) {
        if (info == null) {
            return null;
        }
        String value = info.getProperty(name);
        if (value != null) {
            return value;
        }
        for (String key : info.stringPropertyNames()) {
            if (key.equalsIgnoreCase(name)) {
                return info.getProperty(key);
            }
        }
        return null;
    }
}
