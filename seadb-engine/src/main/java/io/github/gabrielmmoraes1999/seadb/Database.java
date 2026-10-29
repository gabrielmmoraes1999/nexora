package io.github.gabrielmmoraes1999.seadb;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

/**
 * SeaDB. The database is a {@code .db} file: tables, BLOBs,
 * configuration, users, and connections live in it. The {@code .log} beside it
 * stores the committed transactions since creation or the last full backup.
 * If that file does not exist, or is empty, opening creates a log with only the header
 * and advances the generation: the old file can no longer open the database.
 * Starting writes a checkpoint into the database and into the log. Stopping writes it again.
 * A checkpoint may also store pages of a transaction that is still open. That does not commit it.
 * Normal opening refuses pending recovery; explicit log application undoes those transactions.
 * SQL waits for that checkpoint, two open transactions cannot
 * change the same table, and a large before-image is stored whole in the log. The log is not
 * cut until a full backup. Applying a log records only the checkpoint already stored
 * in that file. A rejected log leaves the database checkpoint unchanged.
 *
 * <p>Opening requires a user and password and a file that already exists.
 * {@link #create(Path, DatabaseOptions)} chooses the page size, the password, and the name.
 * The password is stored with bcrypt.
 *
 * <pre>{@code
 * try (Database db = Database.open(Path.of("store.db"), "dba", "dba")) {
 *     db.execute("""
 *             CREATE TABLE customer (
 *                 id INTEGER PRIMARY KEY AUTOINCREMENT,
 *                 name VARCHAR(120) NOT NULL,
 *                 photo BLOB
 *             )
 *             """);
 *     db.execute("INSERT INTO customer (name, photo) VALUES (?, ?)", "Ann", photo);
 * }
 * }</pre>
 *
 * <p>In DBeaver, the driver is {@link io.github.gabrielmmoraes1999.seadb.jdbc.SeaDriver} and the URL
 * is {@code jdbc:seadb:embedded:<file.db>} or {@code jdbc:seadb://host:port/name}.
 */
public final class Database implements AutoCloseable {

    private final Engine engine;
    private Session owner;

    private Database(Engine engine, Session owner) {
        this.engine = engine;
        this.owner = owner;
    }

    /**
     * Creates a new file. The page size is 4096, 8192, 16384, or 32768 and does not change afterward.
     * A file that already has content is not reused.
     */
    public static void create(Path file, DatabaseOptions options) {
        if (file == null) {
            throw new SeaException("database file is required");
        }
        if (options == null) {
            throw new SeaException("database options are required");
        }
        Engine.create(file, options);
    }

    /**
     * Full backup: copies the {@code .db} and the {@code .log} into the directory
     * and makes the live log record only what changes afterward.
     */
    public static void backup(Path database, Path directory) {
        if (database == null) {
            throw new SeaException("database file is required");
        }
        if (directory == null) {
            throw new SeaException("backup directory is required");
        }
        try (DatabaseFile file = DatabaseFile.open(database)) {
            file.backupTo(directory);
        }
    }

    /**
     * Modification backup: copies the {@code .log} into the directory.
     * The live log stays, with the transactions since creation or the last full backup.
     */
    public static void backupLog(Path database, Path directory) {
        if (database == null) {
            throw new SeaException("database file is required");
        }
        if (directory == null) {
            throw new SeaException("backup directory is required");
        }
        try (DatabaseFile file = DatabaseFile.open(database)) {
            file.backupLogTo(directory);
        }
    }

    /**
     * Applies a log onto the {@code .db}. The same generation advances the database
     * from the checkpoint. The next generation brings what changed after the backup.
     */
    public static void applyLog(Path database, Path log) {
        if (database == null) {
            throw new SeaException("database file is required");
        }
        if (log == null) {
            throw new SeaException("transaction log is required");
        }
        applyLog(database, log, null);
    }

    public static void applyLog(Path database, Path log, Cache cache) {
        applyLog(database, log, cache, null);
    }

    /**
     * Applies a log onto the {@code .db}. {@code applied} receives the percent complete,
     * from 1 to 100, when that percent changes. It is not called when the log is rejected.
     */
    public static void applyLog(Path database, Path log, Cache cache, IntConsumer applied) {
        applyLog(database, log, cache, applied, null);
    }

    /**
     * Also reports recovery stages and checkpoint positions, including recovery performed
     * before SQL replay. Messages about reading a checkpoint do not imply a durable apply;
     * the final checkpoint message is sent only after successful application and close.
     */
    public static void applyLog(Path database, Path log, Cache cache, IntConsumer applied,
                                Consumer<String> messages) {
        if (database == null) {
            throw new SeaException("database file is required");
        }
        if (log == null) {
            throw new SeaException("transaction log is required");
        }
        long checkpoint;
        try (Engine engine = Engine.openApplying(database, cache, messages)) {
            if (messages != null) messages.accept("Applying committed transactions from " + log);
            engine.applyExternalLog(log, applied);
            checkpoint = engine.checkpointLsn();
        }
        if (messages != null) messages.accept("Checkpoint applied at log offset " + checkpoint);
    }

    /**
     * Reads the compressed transactions from the {@code .log} and writes the SQL to {@code destination}.
     * {@code source} may be the {@code .log} or the {@code .db} beside it.
     * Parameters are written into the text in place of each {@code ?}.
     */
    public static void exportSql(Path source, Path destination) {
        exportSql(source, destination, null);
    }

    /**
     * Reads the compressed transactions from the {@code .log} and writes the SQL to {@code destination}.
     * {@code progress} receives 0 at the start, advances as each statement is written, and receives 100
     * when the file has been written.
     */
    public static void exportSql(Path source, Path destination, IntConsumer progress) {
        if (source == null) {
            throw new SeaException("transaction log is required");
        }
        if (destination == null) {
            throw new SeaException("the .sql file is required");
        }
        Path log = logOf(source);
        Path target = destination.toAbsolutePath().normalize();
        if (target.equals(log)) {
            throw new SeaException("the SQL destination is a different file");
        }
        if (Files.exists(target) && Files.isDirectory(target)) {
            throw new SeaException("the SQL destination is a file");
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            int[] last = {-1};
            IntConsumer mark = percent -> {
                int shown = Math.max(0, Math.min(99, percent));
                if (shown == last[0]) {
                    return;
                }
                last[0] = shown;
                report(progress, shown);
            };
            try (TransactionLog transactionLog = TransactionLog.inspect(log);
                 BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                long total = Math.max(1L, transactionLog.size());
                mark.accept(0);
                SqlScript script = new SqlScript(writer);
                transactionLog.forEachTransaction(Format.LOG_HEADER_SIZE, at ->
                        mark.accept((int) Math.min(99L, at * 99L / total)), (batch, start) -> {
                    int count = batch.statements.size();
                    script.write(batch, index -> {
                        long span = batch.end - start;
                        long pos = count == 0 ? batch.end : start + span * index / count;
                        mark.accept((int) Math.min(99L, pos * 99L / total));
                    });
                });
                script.finish();
            }
            report(progress, 100);
        } catch (IOException exception) {
            throw new SeaException("could not write the SQL: " + exception.getMessage(), exception);
        }
    }

    private static void report(IntConsumer progress, int percent) {
        if (progress != null) {
            progress.accept(percent);
        }
    }

    private static Path logOf(Path source) {
        Path path = source.toAbsolutePath().normalize();
        Path name = path.getFileName();
        String text = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        if (text.endsWith(".db")) {
            return TransactionLog.beside(path);
        }
        if (text.endsWith(".log")) {
            return path;
        }
        throw new SeaException("provide a .log file or the .db beside it");
    }

    /** Writes the SQL of each commit as it is read, instead of holding the whole script. */
    private static final class SqlScript {
        private final Writer out;
        private String schema;
        private boolean started;

        private SqlScript(Writer out) {
            this.out = out;
        }

        private void write(TransactionLog.Batch batch, IntConsumer written) throws IOException {
            if (started) {
                out.write('\n');
            }
            int index = 0;
            for (TransactionLog.Applied statement : batch.statements) {
                index++;
                if (statement.sql != null && !statement.sql.isBlank()) {
                    if (schema == null || !schema.equalsIgnoreCase(statement.schema)) {
                        schema = statement.schema;
                        out.write("SET SCHEMA ");
                        out.write(schemaName(schema));
                        out.write(";\n");
                    }
                    out.write(bind(statement.sql, statement.params));
                    out.write('\n');
                    started = true;
                }
                if (written != null) {
                    written.accept(index);
                }
            }
        }

        private void finish() throws IOException {
            if (!started) {
                out.write('\n');
            }
        }
    }

    private static String bind(String sql, Object[] params) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        boolean quote = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == '\'') {
                out.append(current);
                if (quote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                    continue;
                }
                quote = !quote;
                continue;
            }
            if (current == '?' && !quote) {
                if (params != null && index < params.length) {
                    out.append(literal(params[index]));
                } else {
                    out.append('?');
                }
                index++;
                continue;
            }
            out.append(current);
        }
        String text = out.toString().strip();
        if (text.endsWith(";")) {
            return text;
        }
        return text + ";";
    }

    private static String schemaName(String schema) {
        if (schema != null && schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return schema;
        }
        return "\"" + (schema == null ? "public" : schema.replace("\"", "\"\"")) + "\"";
    }

    private static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean bool) {
            return bool ? "TRUE" : "FALSE";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Float || value instanceof Double) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            StringBuilder hex = new StringBuilder("X'");
            for (byte piece : bytes) {
                hex.append(String.format("%02X", piece & 0xFF));
            }
            return hex.append('\'').toString();
        }
        if (value instanceof LocalDate date) {
            return "'" + date + "'";
        }
        if (value instanceof LocalTime time) {
            return "'" + time + "'";
        }
        if (value instanceof LocalDateTime time) {
            return "'" + time.toString().replace('T', ' ') + "'";
        }
        if (value instanceof java.sql.Date date) {
            return "'" + date.toLocalDate() + "'";
        }
        if (value instanceof java.sql.Time time) {
            return "'" + time.toLocalTime() + "'";
        }
        if (value instanceof java.sql.Timestamp time) {
            return "'" + time.toLocalDateTime().toString().replace('T', ' ') + "'";
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    public static Database open(Path file, String user, String password) {
        return open(file, user, password, (String) null, null);
    }

    /**
     * Opens the file with a role. {@code null}, empty, or {@code NONE} sign in without a role.
     * The user must have been granted the role, except the DBA.
     */
    public static Database open(Path file, String user, String password, String role) {
        return open(file, user, password, role, null);
    }

    public static Database open(Path file, String user, String password, Cache cache) {
        return open(file, user, password, null, cache);
    }

    public static Database open(Path file, String user, String password, String role, Cache cache) {
        if (user == null || user.isBlank() || password == null || password.isEmpty()) {
            throw new SeaException("user and password are required");
        }
        if (file == null) {
            throw new SeaException("database file is required");
        }
        Engine engine = Engine.open(file, cache);
        try {
            return new Database(engine, engine.login(user, password, role));
        } catch (RuntimeException exception) {
            engine.close();
            throw exception;
        }
    }

    public static Database open(String file, String user, String password) {
        if (file == null || file.isBlank()) {
            throw new SeaException("database file is required");
        }
        return open(Path.of(file), user, password);
    }

    /**
     * Opens the file without authenticating. The network server holds the {@code .db} this way
     * and calls {@link #login} for each client. The file must already exist.
     */
    public static Database attach(Path file) {
        return attach(file, null);
    }

    public static Database attach(Path file, Cache cache) {
        if (file == null) {
            throw new SeaException("database file is required");
        }
        return new Database(Engine.open(file, cache), null);
    }

    /**
     * Runs one or more statements separated by {@code ';'}.
     * The return value is the result of the last statement.
     * {@code ?} placeholders are positional and apply to the whole script.
     */
    public SqlResult execute(String sql, Object... params) {
        synchronized (engine) {
            if (owner == null || owner.closed()) {
                throw new SeaException("session closed");
            }
            return engine.execute(owner, sql, params);
        }
    }

    public SqlResult execute(Session session, String sql, Object... params) {
        synchronized (engine) {
            return engine.execute(session, sql, params);
        }
    }

    public Session login(String user, String password) {
        return login(user, password, null, null);
    }

    /**
     * Signs in with a role already granted. The DBA may use any existing role.
     * The recorded origin is this process.
     */
    public Session login(String user, String password, String role) {
        return login(user, password, role, null);
    }

    /**
     * Signs in and records the remote origin in {@code SEA$CONNECTIONS}.
     * A null {@code remote} uses the local process.
     */
    public Session login(String user, String password, String role, RemoteClient remote) {
        synchronized (engine) {
            return engine.login(user, password, role, remote);
        }
    }

    public void logout(Session session) {
        synchronized (engine) {
            engine.detach(session);
            if (owner == session) {
                owner = null;
            }
        }
    }

    public Session session() {
        return owner;
    }

    public int executeUpdate(String sql, Object... params) {
        SqlResult result = execute(sql, params);
        if (result.isQuery()) {
            throw new SeaException("the statement returned rows; use query or execute");
        }
        return result.affectedRows();
    }

    public SqlResult query(String sql, Object... params) {
        SqlResult result = execute(sql, params);
        if (!result.isQuery()) {
            throw new SeaException("the statement is not a query");
        }
        return result;
    }

    public List<String> tables() {
        synchronized (engine) {
            return engine.tableNames();
        }
    }

    /**
     * Schemas visible to this connection, in name order. Every database has {@code public}.
     */
    public List<String> schemas() {
        synchronized (engine) {
            return engine.schemaNames(owner);
        }
    }

    public List<String> schemas(Session session) {
        synchronized (engine) {
            return engine.schemaNames(session);
        }
    }

    /**
     * Sets the session schema. An unquoted name is case-insensitive.
     * The default, on connect, is {@code public}.
     */
    public void setSchema(Session session, String schema) {
        synchronized (engine) {
            engine.useSchema(session, schema);
        }
    }

    /**
     * Describes the user tables, in name order, with columns and indexes.
     * {@code SEA$} tables are omitted.
     */
    public List<TableDescription> describeTables() {
        synchronized (engine) {
            return engine.describeTables(owner);
        }
    }

    public List<TableDescription> describeTables(Session session) {
        synchronized (engine) {
            return engine.describeTables(session);
        }
    }

    public List<TableDescription> describeAllTables() {
        synchronized (engine) {
            return engine.describeAllTables(owner);
        }
    }

    public List<TableDescription> describeAllTables(Session session) {
        synchronized (engine) {
            return engine.describeAllTables(session);
        }
    }

    /**
     * Describes the views visible to this connection, in name order.
     * Each one is read-only and has no index.
     */
    public List<TableDescription> describeViews() {
        synchronized (engine) {
            return engine.describeViews(owner);
        }
    }

    public List<TableDescription> describeViews(Session session) {
        synchronized (engine) {
            return engine.describeViews(session);
        }
    }

    /**
     * Checkpoints the open database and returns the files a backup will copy.
     * Only a DBA may call this. Writers must wait until {@link #finishBackup}
     * or {@link #abortBackup}. A full backup passes {@code incremental} as false
     * and advances the live log only when {@link #finishBackup} runs.
     */
    public List<BackupPiece> beginBackup(Session session, boolean incremental) {
        synchronized (engine) {
            requireDba(session);
            return engine.beginBackup(incremental);
        }
    }

    /** Reads one slice of a file returned by {@link #beginBackup}. */
    public byte[] readBackup(Session session, int index, long offset, int length) {
        synchronized (engine) {
            requireDba(session);
            return engine.readBackup(index, offset, length);
        }
    }

    /**
     * Ends a backup started by this session. A full backup then makes the live
     * log record only what changes afterward.
     */
    public void finishBackup(Session session) {
        synchronized (engine) {
            requireDba(session);
            engine.finishBackup();
        }
    }

    /** Drops a backup that will not be kept. The live log stays as it is. */
    public void abortBackup(Session session) {
        synchronized (engine) {
            engine.abortBackup();
        }
    }

    private static void requireDba(Session session) {
        if (session == null || session.closed()) {
            throw new SeaException("session closed");
        }
        if (!session.admin()) {
            throw new SeaException("only the DBA can backup the database");
        }
    }

    public Path file() {
        return engine.file;
    }

    /**
     * Page size, in bytes. It is the value stored when the file was created.
     */
    public int pageSize() {
        return engine.pageSize();
    }

    /** Bytes the page cache may occupy after it was rounded up to whole pages. */
    public long cacheMaximum() {
        return engine.cacheMaximum();
    }

    public boolean transactionActive() {
        synchronized (engine) {
            return engine.transactionActive();
        }
    }

    /**
     * The session has {@code BEGIN} without {@code COMMIT} or {@code ROLLBACK}.
     * Another session may have its own transaction at the same time.
     */
    public boolean transactionActive(Session session) {
        synchronized (engine) {
            return engine.transactionActive(session);
        }
    }

    long checkpointLsn() {
        synchronized (engine) {
            return engine.checkpointLsn();
        }
    }

    long logLength() {
        synchronized (engine) {
            return engine.logLength();
        }
    }

    /**
     * Writes dirty pages, including those of transactions that are still open.
     * The open transactions stay open. {@code COMMIT} is a separate operation.
     */
    public void checkpoint() {
        synchronized (engine) {
            if (owner == null || owner.closed()) {
                throw new SeaException("session closed");
            }
            engine.checkpoint();
        }
    }

    /** Replaces the thresholds of the automatic checkpoint. */
    public void checkpointPolicy(CheckpointPolicy policy) {
        synchronized (engine) {
            engine.checkpointPolicy(policy);
        }
    }

    /** Drops the process without rolling back and without a checkpoint. */
    void crash() {
        synchronized (engine) {
            owner = null;
            engine.crash();
        }
    }

    @Override
    public void close() {
        synchronized (engine) {
            try {
                if (owner != null && !owner.closed()) {
                    engine.discardTransaction();
                    engine.detach(owner);
                }
            } finally {
                owner = null;
                engine.close();
            }
        }
    }
}
