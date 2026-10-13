package io.github.gabrielmmoraes1999.nexora.jdbc;

import io.github.gabrielmmoraes1999.nexora.NexException;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Driver JDBC para abrir um arquivo do Nexora no DBeaver e em {@link DriverManager}.
 *
 * <p>Classe: {@code io.github.gabrielmmoraes1999.nexora.jdbc.NexDriver}<br>
 * Embutido: {@code jdbc:nexora:embedded:C:/dados/loja.db}<br>
 * Rede: {@code jdbc:nexora://192.168.0.10:4580/loja}<br>
 * Usuário inicial: {@code dba}<br>
 * Senha inicial: {@code dba}<br>
 * O arquivo {@code .db} já existe. Quem o cria é o CLI do Nexora. Sem o {@code .log} ao lado, a abertura grava um log novo, só com o cabeçalho, e o log antigo deixa de servir.
 */
public final class NexDriver implements Driver {

    public static final String URL_PREFIX = "jdbc:nexora:";

    static {
        try {
            DriverManager.registerDriver(new NexDriver());
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    public NexDriver() {
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        NexTarget target;
        try {
            target = NexTarget.parse(url);
        } catch (NexException exception) {
            throw new SQLException(exception.getMessage(), "08001", exception);
        }
        if (target.embedded() && target.path().isEmpty()) {
            throw new SQLException("informe o arquivo na URL jdbc:nexora:<arquivo.db>", "08001");
        }
        String user = property(info, "user");
        String password = property(info, "password");
        if (user == null || user.isBlank() || password == null || password.isEmpty()) {
            throw new SQLException("usuário e senha são obrigatórios", "28000");
        }
        String role = property(info, "roleName");
        if (role == null || role.isBlank()) {
            role = property(info, "role");
        }
        try {
            if (target.embedded()) {
                SharedDatabase.Opened opened = SharedDatabase.open(target.path(), user, password, role);
                return new NexConnection(url, new LocalAccess(opened.shared(), opened.session()));
            }
            return new NexConnection(url, RemoteAccess.connect(
                    target.host(), target.port(), target.database(), user, password, role));
        } catch (NexException exception) {
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
        user.description = "Usuário do banco. O DBA inicial é dba.";
        DriverPropertyInfo password = new DriverPropertyInfo("password", property(info, "password"));
        password.required = true;
        password.description = "Senha do banco. A senha inicial do DBA é dba.";
        DriverPropertyInfo role = new DriverPropertyInfo("roleName", property(info, "roleName"));
        role.required = false;
        role.description = "Papel da conexão. Vazio ou NONE entra sem papel.";
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
        NexTarget target = NexTarget.parse(url);
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
