package io.github.gabrielmmoraes1999.seadb.jdbc;

import io.github.gabrielmmoraes1999.seadb.Row;
import io.github.gabrielmmoraes1999.seadb.SqlResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

final class SeaResultSet implements ResultSet {

    private final SeaStatement statement;
    private final List<JdbcColumn> columns;
    private final List<Object[]> rows;
    private int cursor = -1;
    private boolean wasNull;
    private boolean closed;
    private int fetchSize;
    private int fetchDirection = FETCH_FORWARD;

    private SeaResultSet(SeaStatement statement, List<JdbcColumn> columns, List<Object[]> rows) {
        this.statement = statement;
        this.columns = columns;
        this.rows = rows;
    }

    static SeaResultSet query(SeaStatement statement, SqlResult result, int maxRows) {
        List<JdbcColumn> columns = new ArrayList<>();
        for (var meta : result.columns()) {
            columns.add(JdbcColumn.of(meta));
        }
        List<Object[]> rows = new ArrayList<>();
        int limit = result.rows().size();
        if (maxRows > 0) {
            limit = Math.min(limit, maxRows);
        }
        for (int i = 0; i < limit; i++) {
            Row row = result.rows().get(i);
            Object[] values = new Object[row.size()];
            for (int column = 0; column < row.size(); column++) {
                values[column] = row.get(column);
            }
            rows.add(values);
        }
        return new SeaResultSet(statement, columns, rows);
    }

    static SeaResultSet metadata(List<JdbcColumn> columns, List<Object[]> rows) {
        return new SeaResultSet(null, columns, rows);
    }

    @Override
    public boolean next() throws SQLException {
        synchronized (lock()) {
            checkOpen();
            if (cursor + 1 < rows.size()) {
                cursor++;
                return true;
            }
            cursor = rows.size();
            return false;
        }
    }

    @Override
    public void close() throws SQLException {
        synchronized (lock()) {
            if (closed) {
                return;
            }
            closed = true;
            if (statement != null) {
                statement.onResultClosed(this);
            }
        }
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Object value = current(columnIndex);
        return value == null ? null : asString(value);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Object value = current(columnIndex);
        return value != null && asBoolean(value);
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        Number number = number(columnIndex);
        return number == null ? 0 : number.byteValue();
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        Number number = number(columnIndex);
        return number == null ? 0 : number.shortValue();
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Number number = number(columnIndex);
        return number == null ? 0 : number.intValue();
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        Number number = number(columnIndex);
        return number == null ? 0 : number.longValue();
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        Number number = number(columnIndex);
        return number == null ? 0 : number.floatValue();
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Number number = number(columnIndex);
        return number == null ? 0 : number.doubleValue();
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal decimal = getBigDecimal(columnIndex);
        return decimal == null ? null : decimal.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        Object value = current(columnIndex);
        return value == null ? null : asBytes(value);
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        Object value = current(columnIndex);
        if (value == null) {
            return null;
        }
        return Date.valueOf(asLocalDate(value));
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        Object value = current(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalTime time) {
            return sqlTime(time);
        }
        LocalDateTime timestamp = asLocalDateTime(value);
        if (timestamp != null) {
            return sqlTime(timestamp.toLocalTime());
        }
        asLocalDate(value);
        return Time.valueOf(LocalTime.MIDNIGHT);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        Object value = current(columnIndex);
        if (value == null) {
            return null;
        }
        LocalDateTime timestamp = asLocalDateTime(value);
        if (timestamp == null) {
            return Timestamp.valueOf(asLocalDate(value).atStartOfDay());
        }
        return Timestamp.valueOf(timestamp);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String text = getString(columnIndex);
        return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] bytes = getBytes(columnIndex);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return getUnicodeStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
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
    public String getCursorName() throws SQLException {
        throw unsupported();
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new SeaResultSetMetaData(columns);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return current(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        if (columnLabel == null) {
            throw new SQLException("column name is required");
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(columnLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("column not found: " + columnLabel, "42S22");
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String text = getString(columnIndex);
        return text == null ? null : new StringReader(text);
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Object value = current(columnIndex);
        return value == null ? null : asDecimal(value);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return cursor < 0 && !rows.isEmpty();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return cursor >= rows.size() && !rows.isEmpty();
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return cursor == 0 && !rows.isEmpty();
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        return cursor >= 0 && cursor == rows.size() - 1;
    }

    @Override
    public void beforeFirst() throws SQLException {
        checkOpen();
        cursor = -1;
    }

    @Override
    public void afterLast() throws SQLException {
        checkOpen();
        cursor = rows.size();
    }

    @Override
    public boolean first() throws SQLException {
        return absolute(1);
    }

    @Override
    public boolean last() throws SQLException {
        return absolute(-1);
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return cursor < 0 || cursor >= rows.size() ? 0 : cursor + 1;
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        checkOpen();
        if (row == 0) {
            cursor = -1;
            return false;
        }
        if (row > 0) {
            if (row > rows.size()) {
                cursor = rows.size();
                return false;
            }
            cursor = row - 1;
            return true;
        }
        int index = rows.size() + row;
        if (index < 0) {
            cursor = -1;
            return false;
        }
        cursor = index;
        return true;
    }

    @Override
    public boolean relative(int rowsDelta) throws SQLException {
        checkOpen();
        if (rowsDelta == 0) {
            return onRow();
        }
        int target = cursor + rowsDelta;
        if (target < 0) {
            cursor = -1;
            return false;
        }
        if (target >= rows.size()) {
            cursor = rows.size();
            return false;
        }
        cursor = target;
        return true;
    }

    @Override
    public boolean previous() throws SQLException {
        return relative(-1);
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != FETCH_FORWARD && direction != FETCH_REVERSE && direction != FETCH_UNKNOWN) {
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
    public int getType() throws SQLException {
        checkOpen();
        return TYPE_SCROLL_INSENSITIVE;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public void insertRow() throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateRow() throws SQLException {
        throw unsupported();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw unsupported();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw unsupported();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw unsupported();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw unsupported();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw unsupported();
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return statement;
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return getObject(columnIndex);
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(columnLabel), map);
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return getRef(findColumn(columnLabel));
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return getBlob(findColumn(columnLabel));
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return getClob(findColumn(columnLabel));
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return getArray(findColumn(columnLabel));
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        return getDate(columnIndex);
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return getDate(findColumn(columnLabel), cal);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return getTime(columnIndex);
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return getTime(findColumn(columnLabel), cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return getTimestamp(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(columnLabel), cal);
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return getURL(findColumn(columnLabel));
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return getRowId(findColumn(columnLabel));
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return getNClob(findColumn(columnLabel));
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return getSQLXML(findColumn(columnLabel));
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getNString(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getNCharacterStream(findColumn(columnLabel));
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        if (type == null) {
            throw new SQLException("type is required");
        }
        Object value = current(columnIndex);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        if (type == String.class) {
            return type.cast(asString(value));
        }
        if (type == Integer.class) {
            return type.cast(asNumber(value).intValue());
        }
        if (type == Long.class) {
            return type.cast(asNumber(value).longValue());
        }
        if (type == Short.class) {
            return type.cast(asNumber(value).shortValue());
        }
        if (type == Byte.class) {
            return type.cast(asNumber(value).byteValue());
        }
        if (type == Double.class) {
            return type.cast(asNumber(value).doubleValue());
        }
        if (type == Float.class) {
            return type.cast(asNumber(value).floatValue());
        }
        if (type == BigDecimal.class) {
            return type.cast(asDecimal(value));
        }
        if (type == Boolean.class) {
            return type.cast(asBoolean(value));
        }
        if (type == byte[].class) {
            return type.cast(asBytes(value));
        }
        if (type == LocalDate.class) {
            return type.cast(asLocalDate(value));
        }
        if (type == LocalDateTime.class) {
            LocalDateTime timestamp = asLocalDateTime(value);
            if (timestamp == null) {
                timestamp = asLocalDate(value).atStartOfDay();
            }
            return type.cast(timestamp);
        }
        if (type == Date.class) {
            return type.cast(Date.valueOf(asLocalDate(value)));
        }
        if (type == LocalTime.class) {
            if (value instanceof LocalTime time) {
                return type.cast(time);
            }
            LocalDateTime timestamp = asLocalDateTime(value);
            if (timestamp != null) {
                return type.cast(timestamp.toLocalTime());
            }
            throw new SQLException("value is not TIME", "22007");
        }
        if (type == Time.class) {
            if (value instanceof LocalTime time) {
                return type.cast(sqlTime(time));
            }
            LocalDateTime timestamp = asLocalDateTime(value);
            return type.cast(sqlTime(timestamp == null ? LocalTime.MIDNIGHT : timestamp.toLocalTime()));
        }
        if (type == Timestamp.class) {
            LocalDateTime timestamp = asLocalDateTime(value);
            if (timestamp == null) {
                timestamp = asLocalDate(value).atStartOfDay();
            }
            return type.cast(Timestamp.valueOf(timestamp));
        }
        throw new SQLException("cannot convert to " + type.getName(), "22023");
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
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

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        updateNull(findColumn(columnLabel));
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        updateBoolean(findColumn(columnLabel), x);
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        updateByte(findColumn(columnLabel), x);
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        updateShort(findColumn(columnLabel), x);
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        updateInt(findColumn(columnLabel), x);
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        updateLong(findColumn(columnLabel), x);
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        updateFloat(findColumn(columnLabel), x);
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        updateDouble(findColumn(columnLabel), x);
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        updateBigDecimal(findColumn(columnLabel), x);
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        updateString(findColumn(columnLabel), x);
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        updateBytes(findColumn(columnLabel), x);
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        updateDate(findColumn(columnLabel), x);
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        updateTime(findColumn(columnLabel), x);
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        updateTimestamp(findColumn(columnLabel), x);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        updateAsciiStream(findColumn(columnLabel), x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        updateBinaryStream(findColumn(columnLabel), x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        updateCharacterStream(findColumn(columnLabel), reader, length);
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        updateObject(findColumn(columnLabel), x, scaleOrLength);
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        updateObject(findColumn(columnLabel), x);
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        updateRef(findColumn(columnLabel), x);
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        updateBlob(findColumn(columnLabel), x);
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        updateClob(findColumn(columnLabel), x);
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        updateArray(findColumn(columnLabel), x);
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        updateRowId(findColumn(columnLabel), x);
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        updateNString(findColumn(columnLabel), nString);
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        updateNClob(findColumn(columnLabel), nClob);
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        updateSQLXML(findColumn(columnLabel), xmlObject);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        updateNCharacterStream(findColumn(columnLabel), reader, length);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        updateAsciiStream(findColumn(columnLabel), x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        updateBinaryStream(findColumn(columnLabel), x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        updateCharacterStream(findColumn(columnLabel), reader, length);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        updateBlob(findColumn(columnLabel), inputStream, length);
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        updateClob(findColumn(columnLabel), reader, length);
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        updateNClob(findColumn(columnLabel), reader, length);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        updateNCharacterStream(findColumn(columnLabel), reader);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        updateAsciiStream(findColumn(columnLabel), x);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        updateBinaryStream(findColumn(columnLabel), x);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        updateCharacterStream(findColumn(columnLabel), reader);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        updateBlob(findColumn(columnLabel), inputStream);
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        updateClob(findColumn(columnLabel), reader);
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        throw unsupported();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        updateNClob(findColumn(columnLabel), reader);
    }

    private Object lock() {
        return statement == null ? this : statement.lock();
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("the result set is closed", "HY010");
        }
    }

    private boolean onRow() {
        return cursor >= 0 && cursor < rows.size();
    }

    private Object current(int columnIndex) throws SQLException {
        checkOpen();
        if (!onRow()) {
            throw new SQLException("cursor is not on a row", "24000");
        }
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("column index out of range: " + columnIndex, "07009");
        }
        Object value = rows.get(cursor)[columnIndex - 1];
        wasNull = value == null;
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        return value;
    }

    private Number number(int columnIndex) throws SQLException {
        Object value = current(columnIndex);
        return value == null ? null : asNumber(value);
    }

    private static SQLException unsupported() {
        return Jdbc.unsupported("cursor update");
    }

    private static String asString(Object value) {
        if (value instanceof byte[] bytes) {
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte piece : bytes) {
                hex.append(String.format("%02X", piece & 0xFF));
            }
            return hex.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof LocalDateTime timestamp) {
            return timestamp.toString().replace('T', ' ');
        }
        return value.toString();
    }

    private static boolean asBoolean(Object value) throws SQLException {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.longValue() != 0;
        }
        if (value instanceof String text) {
            String folded = text.trim();
            if (folded.equalsIgnoreCase("true") || folded.equals("1")) {
                return true;
            }
            if (folded.equalsIgnoreCase("false") || folded.equals("0")) {
                return false;
            }
        }
        throw new SQLException("value is not BOOLEAN", "22018");
    }

    private static Number asNumber(Object value) throws SQLException {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof Boolean bool) {
            return bool ? 1L : 0L;
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException exception) {
                throw new SQLException("value is not numeric", "22018");
            }
        }
        throw new SQLException("value is not numeric", "22018");
    }

    private static BigDecimal asDecimal(Object value) throws SQLException {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Boolean bool) {
            return bool ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        Number number = asNumber(value);
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof Long || number instanceof Integer || number instanceof Short || number instanceof Byte) {
            return BigDecimal.valueOf(number.longValue());
        }
        return BigDecimal.valueOf(number.doubleValue());
    }

    private static byte[] asBytes(Object value) throws SQLException {
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        throw new SQLException("value is not BLOB", "22018");
    }

    private static LocalDate asLocalDate(Object value) throws SQLException {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof LocalDateTime timestamp) {
            return timestamp.toLocalDate();
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text.trim().substring(0, Math.min(10, text.trim().length())));
            } catch (DateTimeParseException | StringIndexOutOfBoundsException exception) {
                throw new SQLException("invalid date", "22007");
            }
        }
        throw new SQLException("value is not DATE", "22007");
    }

    private static LocalDateTime asLocalDateTime(Object value) throws SQLException {
        if (value instanceof LocalDateTime timestamp) {
            return timestamp;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDate || value instanceof Date) {
            return null;
        }
        if (value instanceof String text) {
            String raw = text.trim().replace(' ', 'T');
            try {
                return LocalDateTime.parse(raw);
            } catch (DateTimeParseException exception) {
                return null;
            }
        }
        return null;
    }

    private static Time sqlTime(LocalTime time) {
        int millis = time.getNano() / 1_000_000;
        return Time.valueOf(time.withNano(millis * 1_000_000));
    }
}
