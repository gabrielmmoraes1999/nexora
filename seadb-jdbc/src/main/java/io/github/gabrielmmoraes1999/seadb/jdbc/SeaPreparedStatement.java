package io.github.gabrielmmoraes1999.seadb.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

final class SeaPreparedStatement extends SeaStatement implements PreparedStatement {

    private static final Object UNSET = new Object();

    private final String sql;
    private final List<Object> parameters = new ArrayList<>();
    private final List<Object[]> batch = new ArrayList<>();

    SeaPreparedStatement(SeaConnection connection, String sql, int resultSetType, int resultSetConcurrency,
                              int holdability) {
        super(connection, resultSetType, resultSetConcurrency, holdability);
        this.sql = sql;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        if (!execute()) {
            throw new SQLException("the statement is not a query");
        }
        return getResultSet();
    }

    @Override
    public int executeUpdate() throws SQLException {
        if (execute()) {
            throw new SQLException("the statement returned rows; use executeQuery");
        }
        return getUpdateCount();
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        put(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setByte(int parameterIndex, byte value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setShort(int parameterIndex, short value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setInt(int parameterIndex, int value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setLong(int parameterIndex, long value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setFloat(int parameterIndex, float value) throws SQLException {
        put(parameterIndex, (double) value);
    }

    @Override
    public void setDouble(int parameterIndex, double value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setString(int parameterIndex, String value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] value) throws SQLException {
        put(parameterIndex, value == null ? null : value.clone());
    }

    @Override
    public void setDate(int parameterIndex, Date value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setTime(int parameterIndex, Time value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp value) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream value, int length) throws SQLException {
        setCharacter(parameterIndex, value, length);
    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream value, int length) throws SQLException {
        throw Jdbc.unsupported("UnicodeStream");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream value, int length) throws SQLException {
        put(parameterIndex, value == null ? null : readBytes(value, length));
    }

    @Override
    public void clearParameters() throws SQLException {
        checkOpen();
        parameters.clear();
    }

    @Override
    public void setObject(int parameterIndex, Object value, int targetSqlType) throws SQLException {
        put(parameterIndex, value);
    }

    @Override
    public void setObject(int parameterIndex, Object value) throws SQLException {
        put(parameterIndex, value instanceof byte[] bytes ? bytes.clone() : value);
    }

    @Override
    public boolean execute() throws SQLException {
        return run(sql, arguments());
    }

    @Override
    public void addBatch() throws SQLException {
        synchronized (connection.lock()) {
            checkOpen();
            batch.add(arguments());
        }
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        put(parameterIndex, reader == null ? null : readChars(reader, length));
    }

    @Override
    public void setRef(int parameterIndex, Ref value) throws SQLException {
        throw Jdbc.unsupported("REF");
    }

    @Override
    public void setBlob(int parameterIndex, Blob value) throws SQLException {
        try {
            put(parameterIndex, value == null ? null : value.getBytes(1, (int) value.length()));
        } catch (SQLException exception) {
            throw exception;
        }
    }

    @Override
    public void setClob(int parameterIndex, Clob value) throws SQLException {
        put(parameterIndex, value == null ? null : value.getSubString(1, (int) value.length()));
    }

    @Override
    public void setArray(int parameterIndex, Array value) throws SQLException {
        throw Jdbc.unsupported("ARRAY");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        ResultSet result = getResultSet();
        return result == null ? null : result.getMetaData();
    }

    @Override
    public void setDate(int parameterIndex, Date value, Calendar cal) throws SQLException {
        setDate(parameterIndex, value);
    }

    @Override
    public void setTime(int parameterIndex, Time value, Calendar cal) throws SQLException {
        setTime(parameterIndex, value);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp value, Calendar cal) throws SQLException {
        setTimestamp(parameterIndex, value);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        put(parameterIndex, null);
    }

    @Override
    public void setURL(int parameterIndex, URL value) throws SQLException {
        throw Jdbc.unsupported("URL");
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw Jdbc.unsupported("ParameterMetaData");
    }

    @Override
    public void setRowId(int parameterIndex, RowId value) throws SQLException {
        throw Jdbc.unsupported("ROWID");
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        setString(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        setCharacterStream(parameterIndex, value, length);
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        setClob(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        put(parameterIndex, reader == null ? null : readChars(reader, length));
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        put(parameterIndex, inputStream == null ? null : readBytes(inputStream, length));
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setClob(parameterIndex, reader, length);
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        throw Jdbc.unsupported("SQLXML");
    }

    @Override
    public void setObject(int parameterIndex, Object value, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(parameterIndex, value);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream value, long length) throws SQLException {
        setCharacter(parameterIndex, value, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream value, long length) throws SQLException {
        put(parameterIndex, value == null ? null : readBytes(value, length));
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        put(parameterIndex, reader == null ? null : readChars(reader, length));
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream value) throws SQLException {
        setAsciiStream(parameterIndex, value, -1);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream value) throws SQLException {
        setBinaryStream(parameterIndex, value, -1);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        setCharacterStream(parameterIndex, reader, -1);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        setCharacterStream(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        setCharacterStream(parameterIndex, reader);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        setBinaryStream(parameterIndex, inputStream);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        setCharacterStream(parameterIndex, reader);
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw new SQLException("use addBatch() without SQL on PreparedStatement");
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
                        if (run(sql, batch.get(i))) {
                            throw new SQLException("SELECT cannot be added to the batch");
                        }
                        counts[i] = getUpdateCount();
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

    private void put(int parameterIndex, Object value) throws SQLException {
        checkOpen();
        if (parameterIndex < 1) {
            throw new SQLException("parameter index starts at 1", "07009");
        }
        while (parameters.size() < parameterIndex) {
            parameters.add(UNSET);
        }
        parameters.set(parameterIndex - 1, value);
    }

    private Object[] arguments() throws SQLException {
        Object[] values = new Object[parameters.size()];
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i) == UNSET) {
                throw new SQLException("parameter " + (i + 1) + " was not set", "07001");
            }
            values[i] = parameters.get(i);
        }
        return values;
    }

    private void setCharacter(int parameterIndex, InputStream value, long length) throws SQLException {
        if (value == null) {
            put(parameterIndex, null);
            return;
        }
        put(parameterIndex, new String(readBytes(value, length), StandardCharsets.ISO_8859_1));
    }

    private static byte[] readBytes(InputStream input, long length) throws SQLException {
        try {
            if (length < 0) {
                return input.readAllBytes();
            }
            if (length > Integer.MAX_VALUE) {
                throw new SQLException("stream is too large");
            }
            return input.readNBytes((int) length);
        } catch (IOException exception) {
            throw new SQLException(exception.getMessage(), exception);
        }
    }

    private static String readChars(Reader reader, long length) throws SQLException {
        try {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            long remaining = length < 0 ? Long.MAX_VALUE : length;
            while (remaining > 0) {
                int count = reader.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    break;
                }
                builder.append(buffer, 0, count);
                remaining -= count;
            }
            return builder.toString();
        } catch (IOException exception) {
            throw new SQLException(exception.getMessage(), exception);
        }
    }
}
