package io.github.gabrielmmoraes1999.nexora.server;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.NexException;
import io.github.gabrielmmoraes1999.nexora.RemoteClient;
import io.github.gabrielmmoraes1999.nexora.Session;
import io.github.gabrielmmoraes1999.nexora.SqlResult;
import io.github.gabrielmmoraes1999.nexora.TableDescription;
import io.github.gabrielmmoraes1999.nexora.net.Wire;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publica os arquivos do {@link MotorConfig} numa porta TCP.
 * Cada cliente autentica e o SQL roda no processo do motor.
 * Várias transações ficam abertas; cada uma grava numa cópia própria
 * e o commit publica as linhas se ninguém confirmou as mesmas no meio.
 */
public final class MotorServer implements AutoCloseable {

    private final ServerSocket socket;
    private final List<Published> databases;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Thread acceptor;
    private volatile boolean closed;

    private MotorServer(ServerSocket socket, List<Published> databases) {
        this.socket = socket;
        this.databases = databases;
        this.acceptor = new Thread(this::acceptLoop, "nex-motor");
    }

    public static MotorServer start(MotorConfig config) {
        if (config == null) {
            throw new NexException("a configuração do motor é obrigatória");
        }
        List<Published> opened = new ArrayList<>();
        ServerSocket socket = null;
        try {
            for (MotorConfig.DatabaseSpec spec : config.databases()) {
                opened.add(new Published(spec.name(), Database.attach(spec.path())));
            }
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(config.host(), config.port()));
            MotorServer server = new MotorServer(socket, List.copyOf(opened));
            server.acceptor.start();
            return server;
        } catch (RuntimeException | IOException exception) {
            for (Published published : opened) {
                try {
                    published.database.close();
                } catch (RuntimeException ignored) {
                    // o erro original explica a falha da subida
                }
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // o erro original explica a falha da subida
                }
            }
            if (exception instanceof NexException nex) {
                throw nex;
            }
            if (exception instanceof IOException io) {
                throw new NexException("não foi possível escutar em " + config.host() + ":" + config.port()
                        + ": " + io.getMessage(), io);
            }
            throw (RuntimeException) exception;
        }
    }

    public int port() {
        return socket.getLocalPort();
    }

    public void await() throws InterruptedException {
        acceptor.join();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // o accept sai em seguida
            }
        }
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
                // a conexão já caiu
            }
        }
        try {
            acceptor.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        for (Published published : databases) {
            published.closeAll();
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = socket.accept();
                Thread.ofVirtual().name("nex-cliente").start(() -> serveClient(client));
            } catch (IOException exception) {
                if (closed) {
                    return;
                }
            }
        }
    }

    private void serveClient(Socket socket) {
        clients.add(socket);
        Published current = null;
        Session session = null;
        try {
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            while (!closed) {
                Wire.Frame frame = Wire.readFrame(in);
                try {
                    switch (frame.opcode()) {
                        case Wire.LOGIN -> {
                            if (session != null) {
                                throw new NexException("a conexão já está autenticada");
                            }
                            Wire.Login login = Wire.decodeLogin(frame.body());
                            current = find(login.database());
                            if (current == null) {
                                throw new NexException("banco não encontrado: " + login.database());
                            }
                            session = current.login(login.user(), login.password(), login.role(),
                                    clientOf(socket, login));
                            Wire.writeFrame(out, Wire.OK, Wire.encodeLoginOk(
                                    session.user(), session.schema(), false, false));
                        }
                        case Wire.EXECUTE -> {
                            require(session);
                            Wire.ExecuteRequest request = Wire.decodeExecute(frame.body());
                            Reply reply = current.execute(session, request.sql(), request.params());
                            boolean open = current.transactionOpen(session);
                            Wire.writeFrame(out, Wire.OK, Wire.encodeExecuteOk(
                                    reply.result(), reply.schema(), open, open));
                        }
                        case Wire.SET_SCHEMA -> {
                            require(session);
                            Wire.StateView state = current.setSchema(session, Wire.decodeSchema(frame.body()));
                            boolean open = current.transactionOpen(session);
                            Wire.writeFrame(out, Wire.OK, Wire.encodeState(
                                    state.schema(), open, open));
                        }
                        case Wire.STATUS -> {
                            require(session);
                            Wire.StateView state = current.state(session);
                            boolean open = current.transactionOpen(session);
                            Wire.writeFrame(out, Wire.OK, Wire.encodeState(
                                    state.schema(), open, open));
                        }
                        case Wire.SCHEMAS -> {
                            require(session);
                            Wire.writeFrame(out, Wire.OK, Wire.encodeNames(current.schemas(session)));
                        }
                        case Wire.DESCRIBE_TABLES -> {
                            require(session);
                            Wire.writeFrame(out, Wire.OK, Wire.encodeTables(current.describeTables(session)));
                        }
                        case Wire.DESCRIBE_VIEWS -> {
                            require(session);
                            Wire.writeFrame(out, Wire.OK, Wire.encodeTables(current.describeViews(session)));
                        }
                        case Wire.LOGOUT -> {
                            require(session);
                            current.detach(session);
                            session = null;
                            Wire.writeFrame(out, Wire.OK, new byte[0]);
                            return;
                        }
                        default -> throw new IOException("comando desconhecido");
                    }
                } catch (NexException exception) {
                    Wire.writeFrame(out, Wire.FAIL, Wire.encodeError(exception.getMessage()));
                }
            }
        } catch (EOFException ignored) {
            // o cliente fechou
        } catch (IOException ignored) {
            // a conexão caiu
        } catch (RuntimeException exception) {
            if (!closed) {
                System.err.println("conexão encerrada: " + exception.getMessage());
            }
        } finally {
            clients.remove(socket);
            if (session != null && current != null) {
                try {
                    current.detach(session);
                } catch (RuntimeException ignored) {
                    // o banco pode já ter sido fechado
                }
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // a conexão já caiu
            }
        }
    }

    private static RemoteClient clientOf(Socket socket, Wire.Login login) {
        InetAddress address = socket.getInetAddress();
        String protocol = "TCP";
        String textual = null;
        if (address != null) {
            textual = address.getHostAddress();
            protocol = address instanceof Inet6Address ? "TCPv6" : "TCPv4";
        }
        return new RemoteClient(protocol, textual, login.host(), login.osUser(), login.process());
    }

    private Published find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Published published : databases) {
            if (published.name.equalsIgnoreCase(name)) {
                return published;
            }
        }
        return null;
    }

    private static void require(Session session) {
        if (session == null || session.closed()) {
            throw new NexException("sessão encerrada");
        }
    }

    private record Reply(SqlResult result, String schema) {
    }

    private static final class Published {
        private final String name;
        private final Database database;
        private final List<Session> sessions = new ArrayList<>();

        private Published(String name, Database database) {
            this.name = name;
            this.database = database;
        }

        private Session login(String user, String password, String role, RemoteClient remote) {
            synchronized (database) {
                Session session = database.login(user, password, role, remote);
                sessions.add(session);
                return session;
            }
        }

        private Reply execute(Session session, String sql, Object[] params) {
            synchronized (database) {
                ensure(session);
                SqlResult result = database.execute(session, sql, params == null ? new Object[0] : params);
                return new Reply(result, session.schema());
            }
        }

        private Wire.StateView setSchema(Session session, String schema) {
            synchronized (database) {
                ensure(session);
                database.setSchema(session, schema == null || schema.isBlank() ? "public" : schema);
                return view(session);
            }
        }

        private Wire.StateView state(Session session) {
            synchronized (database) {
                ensure(session);
                return view(session);
            }
        }

        private List<String> schemas(Session session) {
            synchronized (database) {
                ensure(session);
                return database.schemas();
            }
        }

        private List<TableDescription> describeTables(Session session) {
            synchronized (database) {
                ensure(session);
                return database.describeAllTables();
            }
        }

        private List<TableDescription> describeViews(Session session) {
            synchronized (database) {
                ensure(session);
                return database.describeViews();
            }
        }

        private void detach(Session session) {
            synchronized (database) {
                try {
                    if (database.transactionActive(session) && !session.closed()) {
                        database.execute(session, "ROLLBACK");
                    }
                } catch (RuntimeException ignored) {
                    // o logout ainda retira a conexão
                } finally {
                    sessions.remove(session);
                    try {
                        database.logout(session);
                    } catch (RuntimeException ignored) {
                        // o arquivo pode já estar fechado
                    }
                }
            }
        }

        private void closeAll() {
            synchronized (database) {
                for (Session session : List.copyOf(sessions)) {
                    try {
                        if (database.transactionActive(session) && !session.closed()) {
                            database.execute(session, "ROLLBACK");
                        }
                    } catch (RuntimeException ignored) {
                        // segue para soltar o arquivo
                    }
                    try {
                        database.logout(session);
                    } catch (RuntimeException ignored) {
                        // segue para soltar o arquivo
                    }
                }
                sessions.clear();
                database.close();
            }
        }

        private boolean transactionOpen(Session session) {
            return database.transactionActive(session);
        }

        private Wire.StateView view(Session session) {
            boolean open = transactionOpen(session);
            return new Wire.StateView(session.schema(), open, open);
        }

        private void ensure(Session session) {
            if (session == null || session.closed() || !sessions.contains(session)) {
                throw new NexException("sessão encerrada");
            }
        }
    }
}
