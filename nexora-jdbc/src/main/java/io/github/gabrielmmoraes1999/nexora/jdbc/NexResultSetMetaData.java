package io.github.gabrielmmoraes1999.nexora.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

final class NexResultSetMetaData implements ResultSetMetaData {

    private final List<JdbcColumn> columns;

    NexResultSetMetaData(List<JdbcColumn> columns) {
        this.columns = columns;
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        return column(column).autoIncrement();
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        int type = column(column).sqlType();
        return type == Types.CHAR || type == Types.VARCHAR || type == Types.LONGVARCHAR
                || type == Types.CLOB || type == Types.BLOB;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        column(column);
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        column(column);
        return false;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return column(column).nullable();
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return column(column).signed();
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return column(column).displaySize();
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return column(column).name();
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        return column(column).name();
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        String schema = column(column).schema();
        return schema == null ? "" : schema;
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return column(column).precision();
    }

    @Override
    public int getScale(int column) throws SQLException {
        return column(column).scale();
    }

    @Override
    public String getTableName(int column) throws SQLException {
        return column(column).table();
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        column(column);
        return "";
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return column(column).sqlType();
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return column(column).typeName();
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        column(column);
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        column(column);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        column(column);
        return false;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return column(column).className();
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

    private JdbcColumn column(int index) throws SQLException {
        if (index < 1 || index > columns.size()) {
            throw new SQLException("column index out of range: " + index, "07009");
        }
        return columns.get(index - 1);
    }
}
