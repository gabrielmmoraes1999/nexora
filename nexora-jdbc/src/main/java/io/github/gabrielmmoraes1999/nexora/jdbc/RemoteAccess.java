package io.github.gabrielmmoraes1999.nexora.jdbc;

import io.github.gabrielmmoraes1999.nexora.NexException;
import io.github.gabrielmmoraes1999.nexora.RemoteClient;
import io.github.gabrielmmoraes1999.nexora.SqlResult;
import io.github.gabrielmmoraes1999.nexora.TableDescription;
import io.github.gabrielmmoraes1999.nexora.net.Wire;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;

final class RemoteAccess implements DatabaseAccess {

    private final Object lock = new Object();
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private String user;
    private String schema;
    private boolean transactionActive;
    private boolean ownsTransaction;
    private boolean closed;

    private RemoteAccess(Socket socket, DataInputStream input, DataOutputStream output, Wire.SessionView session) {
        this.socket = socket;
        this.input = input;
        this.output = output;
        this.user = session.user();
        this.schema = session.schema();
        this.transactionActive = session.transactionActive();
        this.ownsTransaction = session.ownsTransaction();
    }

    static RemoteAccess connect(String host, int port, String database, String user, String password, String role)
            throws SQLException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setTcpNoDelay(true);
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            RemoteClient local = RemoteClient.local();
            Wire.writeFrame(output, Wire.LOGIN, Wire.encodeLogin(
                    database, user, password, role, local.host(), local.osUser(), local.process()));
            Wire.Frame response = Wire.readFrame(input);
            if (response.opcode() == Wire.FAIL) {
                throw new SQLException(Wire.decodeError(response.body()), "42000");
            }
            if (response.opcode() != Wire.OK) {
                throw new SQLException("resposta inválida do motor", "08001");
            }
            return new RemoteAccess(socket, input, output, Wire.decodeLoginOk(response.body()));
        } catch (SQLException exception) {
            closeQuietly(socket);
            throw exception;
        } catch (IOException exception) {
            closeQuietly(socket);
            throw new SQLException("não foi possível conectar em " + host + ":" + port, "08001", exception);
        }
    }

    @Override
    public Object lock() {
        return lock;
    }

    @Override
    public String user() {
        return user;
    }

    @Override
    public String schema() {
        return schema;
    }

    @Override
    public void refresh() throws SQLException {
        synchronized (lock) {
            ensureOpen();
            try {
                Wire.writeFrame(output, Wire.STATUS, new byte[0]);
                apply(readState());
            } catch (IOException exception) {
                throw failed(exception);
            }
        }
    }

    @Override
    public boolean transactionActive() {
        return transactionActive;
    }

    @Override
    public boolean ownsTransaction() {
        return ownsTransaction;
    }

    @Override
    public SqlResult execute(String sql, Object[] params) throws SQLException {
        synchronized (lock) {
            ensureOpen();
            try {
                Wire.writeFrame(output, Wire.EXECUTE, Wire.encodeExecute(sql, params));
                Wire.Frame response = read();
                Wire.ExecuteReply reply = Wire.decodeExecuteOk(response.body());
                schema = reply.schema();
                transactionActive = reply.transactionActive();
                ownsTransaction = reply.ownsTransaction();
                return reply.result();
            } catch (NexException exception) {
                throw Jdbc.wrap(exception);
            } catch (IOException exception) {
                throw failed(exception);
            }
        }
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        synchronized (lock) {
            ensureOpen();
            try {
                String name = schema == null || schema.isBlank() ? "public" : schema;
                Wire.writeFrame(output, Wire.SET_SCHEMA, Wire.encodeSchema(name));
                apply(readState());
            } catch (IOException exception) {
                throw failed(exception);
            }
        }
    }

    @Override
    public void release() throws SQLException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                Wire.writeFrame(output, Wire.LOGOUT, new byte[0]);
                read();
            } catch (IOException ignored) {
                // o motor pode já ter caído
            } finally {
                closeQuietly(socket);
            }
        }
    }

    @Override
    public List<String> schemas() throws SQLException {
        synchronized (lock) {
            ensureOpen();
            try {
                Wire.writeFrame(output, Wire.SCHEMAS, new byte[0]);
                return Wire.decodeNames(read().body());
            } catch (IOException exception) {
                throw failed(exception);
            }
        }
    }

    @Override
    public List<TableDescription> describeAllTables() throws SQLException {
        return describe(Wire.DESCRIBE_TABLES);
    }

    @Override
    public List<TableDescription> describeViews() throws SQLException {
        return describe(Wire.DESCRIBE_VIEWS);
    }

    private List<TableDescription> describe(byte opcode) throws SQLException {
        synchronized (lock) {
            ensureOpen();
            try {
                Wire.writeFrame(output, opcode, new byte[0]);
                return Wire.decodeTables(read().body());
            } catch (IOException exception) {
                throw failed(exception);
            }
        }
    }

    private Wire.Frame read() throws IOException, SQLException {
        Wire.Frame response = Wire.readFrame(input);
        if (response.opcode() == Wire.FAIL) {
            throw new SQLException(Wire.decodeError(response.body()), "42000");
        }
        if (response.opcode() != Wire.OK) {
            throw new SQLException("resposta inválida do motor", "08001");
        }
        return response;
    }

    private Wire.StateView readState() throws IOException, SQLException {
        return Wire.decodeState(read().body());
    }

    private void apply(Wire.StateView state) {
        schema = state.schema();
        transactionActive = state.transactionActive();
        ownsTransaction = state.ownsTransaction();
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("a conexão está fechada", "08003");
        }
    }

    private static SQLException failed(IOException exception) {
        return new SQLException("conexão com o motor falhou", "08001", exception);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // a conexão já caiu
        }
    }
}
