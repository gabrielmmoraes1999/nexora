package io.github.gabrielmmoraes1999.seadb.jdbc;

import io.github.gabrielmmoraes1999.seadb.SqlResult;
import io.github.gabrielmmoraes1999.seadb.TableDescription;

import java.sql.SQLException;
import java.util.List;

interface DatabaseAccess {

    Object lock();

    String user();

    String schema();

    void refresh() throws SQLException;

    boolean transactionActive();

    boolean ownsTransaction();

    SqlResult execute(String sql, Object[] params) throws SQLException;

    void setSchema(String schema) throws SQLException;

    void release() throws SQLException;

    List<String> schemas() throws SQLException;

    List<TableDescription> describeAllTables() throws SQLException;

    List<TableDescription> describeViews() throws SQLException;
}
