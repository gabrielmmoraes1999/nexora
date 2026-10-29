package io.github.gabrielmmoraes1999.seadb.jdbc;

import io.github.gabrielmmoraes1999.seadb.SqlResult;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class SeaStatement implements Statement {

    final SeaConnection connection;
    private final int resultSetType;
    private final int resultSetConcurrency;
    private final int holdability;
    private final List<String> batch = new ArrayList<>();
    private SeaResultSet result;
    private int updateCount = -1;
    private int maxRows;
    private int maxFieldSize;
    private int queryTimeout;
    private int fetchSize;
    private int fetchDirection = ResultSet.FETCH_FORWARD;
    private boolean escapeProcessing = true;
    private boolean poolable;
    private boolean closeOnCompletion;
    private boolean closingResult;
    private boolean closed;

    SeaStatement(SeaConnection connection, int resultSetType, int resultSetConcurrency, int holdability) {
        this.connection = connection;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.holdability = holdability;
    }

    Object lock() {
        return connection.lock();
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        if (!execute(sql)) {
            throw new SQLException("the statement is not a query");
        }
        return result;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        if (execute(sql)) {
            throw new SQLException("the statement returned rows; use executeQuery");
        }
        return updateCount;
    }

    @Override
    public void close() throws SQLException {
        synchronized (connection.lock()) {
            if (closed) {
                return;
            }
            closed = true;
            closeResult();
            connection.forget(this);
        }
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return maxFieldSize;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        checkOpen();
        if (max < 0) {
            throw new SQLException("maxFieldSize is negative");
        }
        maxFieldSize = max;
    }

    @Override
    public int getMaxRows() throws SQLException {
        checkOpen();
        return maxRows;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        checkOpen();
        if (max < 0) {
            throw new SQLException("maxRows is negative");
        }
        maxRows = max;
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkOpen();
        escapeProcessing = enable;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        checkOpen();
        return queryTimeout;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        checkOpen();
        if (seconds < 0) {
            throw new SQLException("queryTimeout is negative");
        }
        queryTimeout = seconds;
    }

    @Override
    public void cancel() {
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
    public void setCursorName(String name) throws SQLException {
        throw Jdbc.unsupported("named cursor");
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        return run(sql, new Object[0]);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return result;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        return updateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return getMoreResults(CLOSE_CURRENT_RESULT);
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != ResultSet.FETCH_FORWARD
                && direction != ResultSet.FETCH_REVERSE
                && direction != ResultSet.FETCH_UNKNOWN) {
            throw new SQLException("invalid fetch direction: " + direction);
        }
        fetchDirection = direction;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return fetchDirection;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
        if (rows < 0) {
            throw new SQLException("fetchSize is negative");
        }
        fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        checkOpen();
        return resultSetConcurrency;
    }

    @Override
    public int getResultSetType() throws SQLException {
        checkOpen();
        return resultSetType;
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        checkOpen();
        if (sql == null) {
            throw new SQLException("SQL is required");
        }
        synchronized (connection.lock()) {
            batch.add(sql);
        }
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        synchronized (connection.lock()) {
            batch.clear();
        }
    }

    @Override
    public int[] executeBatch() throws SQLException {
        synchronized (connection.lock()) {
            checkOpen();
            try {
                int[] counts = new int[batch.size()];
                for (int i = 0; i < batch.size(); i++) {
                    try {
                        if (run(batch.get(i), new Object[0])) {
                            throw new SQLException("SELECT cannot be added to the batch");
                        }
                        counts[i] = updateCount;
                    } catch (SQLException exception) {
                        throw new BatchUpdateException(exception.getMessage(), exception.getSQLState(),
                                Arrays.copyOf(counts, i), exception);
                    }
                }
                return counts;
            } finally {
                batch.clear();
            }
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkOpen();
        return connection;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        synchronized (connection.lock()) {
            checkOpen();
            closeResult();
            updateCount = -1;
            return false;
        }
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();
        return SeaResultSet.metadata(List.of(JdbcColumn.bigint("GENERATED_KEY")), List.of());
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return execute(sql);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        checkOpen();
        return holdability;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        checkOpen();
        this.poolable = poolable;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        checkOpen();
        return poolable;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        checkOpen();
        closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        checkOpen();
        return closeOnCompletion;
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

    boolean run(String sql, Object[] params) throws SQLException {
        synchronized (connection.lock()) {
            checkOpen();
            closeResult();
            updateCount = -1;
            connection.beforeStatement(sql);
            SqlResult outcome = connection.execute(sql, params);
            if (outcome.isQuery()) {
                result = SeaResultSet.query(this, outcome, maxRows);
                return true;
            }
            updateCount = outcome.affectedRows();
            return false;
        }
    }

    void onResultClosed(SeaResultSet closedResult) {
        synchronized (connection.lock()) {
            if (result == closedResult) {
                result = null;
            }
            if (!closingResult && closeOnCompletion && result == null && !closed) {
                closed = true;
                connection.forget(this);
            }
        }
    }

    void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("the statement is closed", "HY010");
        }
        connection.checkOpen();
    }

    private void closeResult() throws SQLException {
        if (result != null) {
            SeaResultSet open = result;
            result = null;
            closingResult = true;
            try {
                open.close();
            } finally {
                closingResult = false;
            }
        }
    }
}
