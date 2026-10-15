package io.github.gabrielmmoraes1999.nexora.jdbc;

import io.github.gabrielmmoraes1999.nexora.SqlResult;
import io.github.gabrielmmoraes1999.nexora.TableDescription;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.ResultSet;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

final class NexConnection implements Connection {

    private final String url;
    private final DatabaseAccess access;
    private final Set<NexStatement> statements = new LinkedHashSet<>();
    private Map<String, Class<?>> typeMap = new HashMap<>();
    private boolean autoCommit = true;
    private boolean readOnly;
    private boolean closed;
    private int holdability = ResultSet.HOLD_CURSORS_OVER_COMMIT;
    private int isolation = TRANSACTION_SERIALIZABLE;
    private int networkTimeout;

    NexConnection(String url, DatabaseAccess access) {
        this.url = url;
        this.access = access;
    }

    String user() {
        return access.user();
    }

    Object lock() {
        return access.lock();
    }

    List<String> schemas() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            return access.schemas();
        }
    }

    List<TableDescription> describeAllTables() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            return access.describeAllTables();
        }
    }

    List<TableDescription> describeViews() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            return access.describeViews();
        }
    }

    String url() {
        return url;
    }

    @Override
    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, holdability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, holdability);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw Jdbc.unsupported("procedure");
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        checkOpen();
        if (sql == null) {
            throw new SQLException("SQL is required");
        }
        return sql;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        synchronized (lock()) {
            checkOpen();
            if (this.autoCommit == autoCommit) {
                return;
            }
            access.refresh();
            if (autoCommit && access.transactionActive() && access.ownsTransaction()) {
                execute("COMMIT", new Object[0]);
            }
            this.autoCommit = autoCommit;
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            return autoCommit;
        }
    }

    @Override
    public void commit() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            if (autoCommit) {
                throw new SQLException("commit is not valid with auto-commit on", "25000");
            }
            access.refresh();
            if (access.transactionActive()) {
                if (!access.ownsTransaction()) {
                    throw new SQLException("another connection has an open transaction");
                }
                execute("COMMIT", new Object[0]);
            }
        }
    }

    @Override
    public void rollback() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            if (autoCommit) {
                throw new SQLException("rollback is not valid with auto-commit on", "25000");
            }
            access.refresh();
            if (access.transactionActive()) {
                if (!access.ownsTransaction()) {
                    throw new SQLException("another connection has an open transaction");
                }
                execute("ROLLBACK", new Object[0]);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        synchronized (lock()) {
            if (closed) {
                return;
            }
            closed = true;
            SQLException failure = null;
            for (NexStatement statement : List.copyOf(statements)) {
                try {
                    statement.close();
                } catch (SQLException exception) {
                    failure = exception;
                }
            }
            try {
                access.release();
            } catch (SQLException exception) {
                failure = exception;
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new NexDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        synchronized (lock()) {
            checkOpen();
            this.readOnly = readOnly;
        }
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            return readOnly;
        }
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkOpen();
    }

    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        synchronized (lock()) {
            checkOpen();
            if (level == TRANSACTION_NONE) {
                throw new SQLException("isolation TRANSACTION_NONE is not supported", "0A000");
            }
            if (level != TRANSACTION_READ_UNCOMMITTED
                    && level != TRANSACTION_READ_COMMITTED
                    && level != TRANSACTION_REPEATABLE_READ
                    && level != TRANSACTION_SERIALIZABLE) {
                throw new SQLException("invalid isolation level: " + level);
            }
            this.isolation = level;
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            return isolation;
        }
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return createStatement(resultSetType, resultSetConcurrency, holdability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareStatement(sql, resultSetType, resultSetConcurrency, holdability);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw Jdbc.unsupported("procedure");
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            return new HashMap<>(typeMap);
        }
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        synchronized (lock()) {
            checkOpen();
            if (map == null) {
                throw new SQLException("type map is required");
            }
            typeMap = new HashMap<>(map);
        }
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        synchronized (lock()) {
            checkOpen();
            if (holdability != ResultSet.HOLD_CURSORS_OVER_COMMIT && holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
                throw new SQLException("invalid holdability: " + holdability);
            }
            this.holdability = holdability;
        }
    }

    @Override
    public int getHoldability() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            return holdability;
        }
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw Jdbc.unsupported("savepoint");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw Jdbc.unsupported("savepoint");
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw Jdbc.unsupported("savepoint");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw Jdbc.unsupported("savepoint");
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        synchronized (lock()) {
            checkOpen();
            checkResultSet(resultSetType, resultSetConcurrency);
            NexStatement statement = new NexStatement(this, resultSetType, resultSetConcurrency, resultSetHoldability);
            statements.add(statement);
            return statement;
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                                              int resultSetHoldability) throws SQLException {
        synchronized (lock()) {
            checkOpen();
            if (sql == null) {
                throw new SQLException("SQL is required");
            }
            checkResultSet(resultSetType, resultSetConcurrency);
            NexPreparedStatement statement = new NexPreparedStatement(
                    this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
            statements.add(statement);
            return statement;
        }
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        throw Jdbc.unsupported("procedure");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public Clob createClob() throws SQLException {
        throw Jdbc.unsupported("CLOB");
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw Jdbc.unsupported("BLOB");
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw Jdbc.unsupported("NCLOB");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw Jdbc.unsupported("SQLXML");
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException("timeout is negative");
        }
        return !closed;
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkOpen();
        return new Properties();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw Jdbc.unsupported("ARRAY");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw Jdbc.unsupported("STRUCT");
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        synchronized (lock()) {
            checkOpen();
            access.setSchema(schema);
        }
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return access.schema();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) {
            throw new SQLException("executor is required");
        }
        if (!closed) {
            close();
        }
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        synchronized (lock()) {
            checkOpen();
            if (executor == null) {
                throw new SQLException("executor is required");
            }
            if (milliseconds < 0) {
                throw new SQLException("timeout is negative");
            }
            networkTimeout = milliseconds;
        }
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            return networkTimeout;
        }
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("is not " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }

    void forget(NexStatement statement) {
        statements.remove(statement);
    }

    void beforeStatement(String sql) throws SQLException {
        checkOpen();
        if (sql == null) {
            throw new SQLException("SQL is required");
        }
        if (readOnly && !Jdbc.readOnlyCommand(sql)) {
            throw new SQLException("read-only connection", "25006");
        }
        access.refresh();
        if (access.transactionActive() && !access.ownsTransaction() && !"SELECT".equals(Jdbc.firstKeyword(sql))) {
            throw new SQLException("another connection has an open transaction");
        }
        if (!autoCommit && !Jdbc.skipsTransaction(sql) && !access.transactionActive()) {
            execute("BEGIN", new Object[0]);
        }
    }

    SqlResult execute(String sql, Object[] params) throws SQLException {
        return access.execute(sql, params);
    }

    void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("the connection is closed", "08003");
        }
    }

    private static void checkResultSet(int resultSetType, int resultSetConcurrency) throws SQLException {
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY && resultSetType != ResultSet.TYPE_SCROLL_INSENSITIVE) {
            throw Jdbc.unsupported("TYPE_SCROLL_SENSITIVE");
        }
        if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw Jdbc.unsupported("CONCUR_UPDATABLE");
        }
    }
}
