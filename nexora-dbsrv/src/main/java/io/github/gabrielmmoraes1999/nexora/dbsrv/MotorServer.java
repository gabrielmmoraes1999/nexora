package io.github.gabrielmmoraes1999.nexora.dbsrv;

import io.github.gabrielmmoraes1999.nexora.Cache;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes the files from {@link MotorConfig} on a TCP port.
 * Each client authenticates and SQL runs in the server process.
 * Several transactions stay open; each one writes to its own copy,
 * and commit publishes the rows if nobody else committed the same ones in the meantime.
 */
public final class MotorServer implements AutoCloseable {

    private final String host;
    private final ServerSocket socket;
    private final List<Published> databases;
    private final MotorLog log;
    private final Cache cache;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Session, Link> links = new ConcurrentHashMap<>();
    private final AtomicLong nextConnection = new AtomicLong();
    private final Thread acceptor;
    private volatile boolean closed;

    private MotorServer(String host, ServerSocket socket, List<Published> databases, MotorLog log, Cache cache) {
        this.host = host;
        this.socket = socket;
        this.databases = databases;
        this.log = log == null ? MotorLog.QUIET : log;
        this.cache = cache;
        this.acceptor = new Thread(this::acceptLoop, "nex-motor");
    }

    public static MotorServer start(MotorConfig config) {
        return start(config, null);
    }

    /**
     * Opens every database in the configuration, then listens.
     * {@code log} receives the databases, the connections, and the statements.
     * A null log discards those events.
     */
    public static MotorServer start(MotorConfig config, MotorLog log) {
        return start(config, log, Cache.DEFAULT_BYTES);
    }

    /**
     * Opens every database with a shared page cache of {@code cacheBytes}.
     * The size is rounded up to a whole number of pages when each file opens.
     */
    public static MotorServer start(MotorConfig config, MotorLog log, long cacheBytes) {
        if (config == null) {
            throw new NexException("the server configuration is required");
        }
        Cache cache = Cache.of(cacheBytes);
        List<Published> opened = new ArrayList<>();
        ServerSocket socket = null;
        try {
            for (MotorConfig.DatabaseSpec spec : config.databases()) {
                opened.add(new Published(spec.name(), Database.attach(spec.path(), cache)));
            }
            socket = bind(config.host(), config.port());
            MotorServer server = new MotorServer(config.host(), socket, new CopyOnWriteArrayList<>(opened), log, cache);
            server.acceptor.start();
            try {
                for (Published published : opened) {
                    server.log.databaseStarted(published.name, published.database.file(), published.database.pageSize());
                }
            } catch (RuntimeException exception) {
                server.close();
                throw exception;
            }
            return server;
        } catch (RuntimeException | IOException exception) {
            for (Published published : opened) {
                try {
                    published.database.close();
                } catch (RuntimeException ignored) {
                    // the original error explains the startup failure
                }
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // the original error explains the startup failure
                }
            }
            if (exception instanceof NexException nex) {
                throw nex;
            }
            if (exception instanceof IOException io) {
                throw new NexException("could not listen on " + config.host() + ":" + config.port()
                        + ": " + io.getMessage(), io);
            }
            throw (RuntimeException) exception;
        }
    }

    /**
     * Listens with no database open. {@link #publish} adds one later.
     * Port {@code 0} asks the system for a free port.
     */
    public static MotorServer listen(String host, int port, MotorLog log) {
        return listen(host, port, log, Cache.DEFAULT_BYTES);
    }

    public static MotorServer listen(String host, int port, MotorLog log, long cacheBytes) {
        if (host == null || host.isBlank()) {
            throw new NexException("server host is required");
        }
        if (port < 0 || port > 65535) {
            throw new NexException("invalid port: " + port);
        }
        String trimmed = host.trim();
        try {
            ServerSocket socket = bind(trimmed, port);
            MotorServer server = new MotorServer(trimmed, socket, new CopyOnWriteArrayList<>(), log, Cache.of(cacheBytes));
            server.acceptor.start();
            return server;
        } catch (IOException exception) {
            throw new NexException("could not listen on " + trimmed + ":" + port + ": " + exception.getMessage(), exception);
        }
    }

    /**
     * Opens another {@code .db} on a server that is already listening.
     * The name is the one used in {@code jdbc:nexora://host:port/name}.
     */
    public void publish(String name, Path path) {
        if (name == null || name.isBlank()) {
            throw new NexException("database name is required");
        }
        String trimmed = name.trim();
        synchronized (this) {
            if (closed) {
                throw new NexException("the server is shut down");
            }
            if (find(trimmed) != null) {
                throw new NexException("duplicate database name: " + trimmed);
            }
            Published published = new Published(trimmed, Database.attach(path, cache));
            databases.add(published);
            log.databaseStarted(published.name, published.database.file(), published.database.pageSize());
        }
    }

    public String host() {
        return host;
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** Page-cache ceiling after rounding up to whole pages. */
    public long cacheMaximum() {
        return cache.maximum();
    }

    public int databaseCount() {
        return databases.size();
    }

    public int connections() {
        int count = 0;
        for (Published published : databases) {
            synchronized (published.database) {
                count += published.sessions.size();
            }
        }
        return count;
    }

    private static ServerSocket bind(String host, int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(host, port));
            return socket;
        } catch (IOException exception) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // the bind error is the one that matters
            }
            throw exception;
        }
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
                // accept returns right after
            }
        }
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
                // the connection already closed
            }
        }
        try {
            acceptor.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        for (Session session : List.copyOf(links.keySet())) {
            forget(session);
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
            String address = addressOf(socket);
            while (!closed) {
                Wire.Frame frame = Wire.readFrame(in);
                try {
                    switch (frame.opcode()) {
                        case Wire.LOGIN -> {
                            if (session != null) {
                                throw new NexException("the connection is already authenticated");
                            }
                            Wire.Login login = Wire.decodeLogin(frame.body());
                            current = find(login.database());
                            if (current == null) {
                                log.failed("Database not found: " + login.database() + " from " + address);
                                throw new NexException("database not found: " + login.database());
                            }
                            try {
                                session = current.login(login.user(), login.password(), login.role(),
                                        clientOf(socket, login));
                            } catch (NexException exception) {
                                log.failed("Login failed for \"" + login.user() + "\" on database \""
                                        + login.database() + "\" from " + address + ": " + exception.getMessage());
                                throw exception;
                            }
                            long id = nextConnection.incrementAndGet();
                            links.put(session, new Link(id, current.name, session.user(), address));
                            log.connected(id, current.name, session.user(), address);
                            Wire.writeFrame(out, Wire.OK, Wire.encodeLoginOk(
                                    session.user(), session.schema(), false, false));
                        }
                        case Wire.EXECUTE -> {
                            require(session);
                            Wire.ExecuteRequest request = Wire.decodeExecute(frame.body());
                            log.statement(current.name, session.user(), request.sql());
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
                            forget(session);
                            current.detach(session);
                            session = null;
                            Wire.writeFrame(out, Wire.OK, new byte[0]);
                            return;
                        }
                        default -> throw new IOException("unknown command");
                    }
                } catch (NexException exception) {
                    Wire.writeFrame(out, Wire.FAIL, Wire.encodeError(exception.getMessage()));
                }
            }
        } catch (EOFException ignored) {
            // the client closed
        } catch (IOException ignored) {
            // the connection closed
        } catch (RuntimeException exception) {
            if (!closed) {
                String message = "Connection closed: " + exception.getMessage();
                System.err.println(message);
                log.failed(message);
            }
        } finally {
            clients.remove(socket);
            if (session != null && current != null) {
                try {
                    forget(session);
                    current.detach(session);
                } catch (RuntimeException ignored) {
                    // the database may already be closed
                }
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // the connection already closed
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

    private void forget(Session session) {
        Link link = links.remove(session);
        if (link != null) {
            log.disconnected(link.id, link.database, link.user, link.address);
        }
    }

    private static String addressOf(Socket socket) {
        InetAddress address = socket.getInetAddress();
        if (address == null || address.getHostAddress() == null || address.getHostAddress().isBlank()) {
            return "unknown";
        }
        return address.getHostAddress();
    }

    private static void require(Session session) {
        if (session == null || session.closed()) {
            throw new NexException("session closed");
        }
    }

    private record Reply(SqlResult result, String schema) {
    }

    private record Link(long id, String database, String user, String address) {
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
                return database.schemas(session);
            }
        }

        private List<TableDescription> describeTables(Session session) {
            synchronized (database) {
                ensure(session);
                return database.describeAllTables(session);
            }
        }

        private List<TableDescription> describeViews(Session session) {
            synchronized (database) {
                ensure(session);
                return database.describeViews(session);
            }
        }

        private void detach(Session session) {
            synchronized (database) {
                try {
                    if (database.transactionActive(session) && !session.closed()) {
                        database.execute(session, "ROLLBACK");
                    }
                } catch (RuntimeException ignored) {
                    // logout still removes the connection
                } finally {
                    sessions.remove(session);
                    try {
                        database.logout(session);
                    } catch (RuntimeException ignored) {
                        // the file may already be closed
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
                        // continue on to release the file
                    }
                    try {
                        database.logout(session);
                    } catch (RuntimeException ignored) {
                        // continue on to release the file
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
                throw new NexException("session closed");
            }
        }
    }
}
