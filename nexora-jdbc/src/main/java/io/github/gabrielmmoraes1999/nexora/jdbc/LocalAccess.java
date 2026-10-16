package io.github.gabrielmmoraes1999.nexora.jdbc;

import io.github.gabrielmmoraes1999.nexora.NexException;
import io.github.gabrielmmoraes1999.nexora.Session;
import io.github.gabrielmmoraes1999.nexora.SqlResult;
import io.github.gabrielmmoraes1999.nexora.TableDescription;

import java.sql.SQLException;
import java.util.List;

final class LocalAccess implements DatabaseAccess {

    private final SharedDatabase shared;
    private final Session session;

    LocalAccess(SharedDatabase shared, Session session) {
        this.shared = shared;
        this.session = session;
    }

    @Override
    public Object lock() {
        return shared;
    }

    @Override
    public String user() {
        return session.user();
    }

    @Override
    public String schema() {
        return session.schema();
    }

    @Override
    public void refresh() {
    }

    @Override
    public boolean transactionActive() {
        return shared.database().transactionActive(session);
    }

    @Override
    public boolean ownsTransaction() {
        return shared.database().transactionActive(session);
    }

    @Override
    public SqlResult execute(String sql, Object[] params) throws SQLException {
        try {
            return shared.database().execute(session, sql, params == null ? new Object[0] : params);
        } catch (NexException exception) {
            throw Jdbc.wrap(exception);
        }
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        try {
            shared.database().setSchema(session, schema == null || schema.isBlank() ? "public" : schema);
        } catch (RuntimeException exception) {
            throw Jdbc.wrap(exception);
        }
    }

    @Override
    public void release() throws SQLException {
        try {
            shared.release(session);
        } catch (NexException exception) {
            throw Jdbc.wrap(exception);
        }
    }

    @Override
    public List<String> schemas() throws SQLException {
        try {
            return shared.database().schemas(session);
        } catch (NexException exception) {
            throw Jdbc.wrap(exception);
        }
    }

    @Override
    public List<TableDescription> describeAllTables() throws SQLException {
        try {
            return shared.database().describeAllTables(session);
        } catch (NexException exception) {
            throw Jdbc.wrap(exception);
        }
    }

    @Override
    public List<TableDescription> describeViews() throws SQLException {
        try {
            return shared.database().describeViews(session);
        } catch (NexException exception) {
            throw Jdbc.wrap(exception);
        }
    }
}
