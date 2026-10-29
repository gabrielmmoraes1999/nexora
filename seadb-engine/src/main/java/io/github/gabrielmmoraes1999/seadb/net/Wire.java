package io.github.gabrielmmoraes1999.seadb.net;

import io.github.gabrielmmoraes1999.seadb.BackupPiece;
import io.github.gabrielmmoraes1999.seadb.ColumnMeta;
import io.github.gabrielmmoraes1999.seadb.DataType;
import io.github.gabrielmmoraes1999.seadb.SeaException;
import io.github.gabrielmmoraes1999.seadb.ReferentialAction;
import io.github.gabrielmmoraes1999.seadb.Row;
import io.github.gabrielmmoraes1999.seadb.SqlResult;
import io.github.gabrielmmoraes1999.seadb.TableDescription;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Server frames. Each frame is the length, the code, and the body.
 * The password travels in the login frame, unencrypted.
 */
public final class Wire {

    public static final int MAGIC = 0x4E455831;
    public static final int VERSION = 1;
    public static final int MAX_FRAME = 64 * 1024 * 1024;

    public static final byte OK = 0;
    public static final byte FAIL = (byte) 255;
    public static final byte LOGIN = 1;
    public static final byte EXECUTE = 2;
    public static final byte SET_SCHEMA = 3;
    public static final byte LOGOUT = 4;
    public static final byte SCHEMAS = 5;
    public static final byte DESCRIBE_TABLES = 6;
    public static final byte DESCRIBE_VIEWS = 7;
    public static final byte STATUS = 8;
    public static final byte BACKUP = 9;
    public static final byte BACKUP_READ = 10;
    public static final byte BACKUP_FINISH = 11;
    public static final byte BACKUP_ABORT = 12;

    private static final byte V_NULL = 0;
    private static final byte V_BOOLEAN = 1;
    private static final byte V_BYTE = 2;
    private static final byte V_SHORT = 3;
    private static final byte V_INT = 4;
    private static final byte V_LONG = 5;
    private static final byte V_FLOAT = 6;
    private static final byte V_DOUBLE = 7;
    private static final byte V_DECIMAL = 8;
    private static final byte V_STRING = 9;
    private static final byte V_BYTES = 10;
    private static final byte V_DATE = 11;
    private static final byte V_TIME = 12;
    private static final byte V_TIMESTAMP = 13;
    private static final byte V_SQL_DATE = 14;
    private static final byte V_SQL_TIME = 15;
    private static final byte V_SQL_TIMESTAMP = 16;
    private static final byte V_UTIL_DATE = 17;

    private Wire() {
    }

    public record Frame(byte opcode, byte[] body) {
    }

    public record Login(String database, String user, String password, String role,
                        String host, String osUser, String process) {
    }

    public record SessionView(String user, String schema, boolean transactionActive, boolean ownsTransaction) {
    }

    public record ExecuteRequest(String sql, Object[] params) {
    }

    public record ExecuteReply(SqlResult result, String schema, boolean transactionActive, boolean ownsTransaction) {
    }

    public record StateView(String schema, boolean transactionActive, boolean ownsTransaction) {
    }

    public static void writeFrame(OutputStream output, byte opcode, byte[] body) throws IOException {
        byte[] payload = body == null ? new byte[0] : body;
        long length = 1L + payload.length;
        if (length < 1 || length > MAX_FRAME) {
            throw new IOException("frame exceeds the limit");
        }
        DataOutputStream out = output instanceof DataOutputStream data ? data : new DataOutputStream(output);
        out.writeInt((int) length);
        out.writeByte(opcode);
        out.write(payload);
        out.flush();
    }

    public static Frame readFrame(InputStream input) throws IOException {
        DataInputStream in = input instanceof DataInputStream data ? data : new DataInputStream(input);
        int length;
        try {
            length = in.readInt();
        } catch (EOFException exception) {
            throw exception;
        }
        if (length < 1 || length > MAX_FRAME) {
            throw new IOException("invalid frame");
        }
        byte opcode = in.readByte();
        byte[] body = new byte[length - 1];
        in.readFully(body);
        return new Frame(opcode, body);
    }

    public static byte[] encodeLogin(String database, String user, String password, String role) {
        return encodeLogin(database, user, password, role, null, null, null);
    }

    public static byte[] encodeLogin(String database, String user, String password, String role,
                                     String host, String osUser, String process) {
        return bytes(out -> {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            writeUtf(out, database);
            writeUtf(out, user);
            writeUtf(out, password);
            writeUtf(out, role == null ? "" : role);
            writeUtf(out, host);
            writeUtf(out, osUser);
            writeUtf(out, process);
        });
    }

    public static Login decodeLogin(byte[] body) throws IOException {
        DataInputStream in = input(body);
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("invalid protocol");
        }
        int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("unsupported protocol version: " + version);
        }
        String database = readUtf(in);
        String user = readUtf(in);
        String password = readUtf(in);
        String role = readUtf(in);
        String host = in.available() > 0 ? readUtf(in) : "";
        String osUser = in.available() > 0 ? readUtf(in) : "";
        String process = in.available() > 0 ? readUtf(in) : "";
        return new Login(database, user, password, role, blank(host), blank(osUser), blank(process));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public static byte[] encodeLoginOk(String user, String schema, boolean transactionActive, boolean ownsTransaction) {
        return bytes(out -> {
            writeUtf(out, user);
            writeState(out, schema, transactionActive, ownsTransaction);
        });
    }

    public static SessionView decodeLoginOk(byte[] body) throws IOException {
        DataInputStream in = input(body);
        String user = readUtf(in);
        StateView state = readState(in);
        return new SessionView(user, state.schema(), state.transactionActive(), state.ownsTransaction());
    }

    public static byte[] encodeExecute(String sql, Object[] params) {
        Object[] values = params == null ? new Object[0] : params;
        return bytes(out -> {
            writeUtf(out, sql);
            out.writeInt(values.length);
            for (Object value : values) {
                writeValue(out, value);
            }
        });
    }

    public static ExecuteRequest decodeExecute(byte[] body) throws IOException {
        DataInputStream in = input(body);
        String sql = readUtf(in);
        int count = in.readInt();
        if (count < 0 || count > MAX_FRAME) {
            throw new IOException("invalid frame");
        }
        Object[] params = new Object[count];
        for (int i = 0; i < count; i++) {
            params[i] = readValue(in);
        }
        return new ExecuteRequest(sql, params);
    }

    public static byte[] encodeExecuteOk(SqlResult result, String schema, boolean transactionActive, boolean ownsTransaction) {
        return bytes(out -> {
            out.writeBoolean(result.isQuery());
            if (result.isQuery()) {
                List<ColumnMeta> columns = result.columns();
                out.writeInt(columns.size());
                for (ColumnMeta column : columns) {
                    writeColumn(out, column);
                }
                List<Row> rows = result.rows();
                out.writeInt(rows.size());
                for (Row row : rows) {
                    out.writeInt(row.size());
                    for (int i = 0; i < row.size(); i++) {
                        writeValue(out, row.get(i));
                    }
                }
            } else {
                out.writeInt(result.affectedRows());
            }
            writeState(out, schema, transactionActive, ownsTransaction);
        });
    }

    public static ExecuteReply decodeExecuteOk(byte[] body) throws IOException {
        DataInputStream in = input(body);
        boolean query = in.readBoolean();
        SqlResult result;
        if (query) {
            int columnCount = in.readInt();
            if (columnCount < 0) {
                throw new IOException("invalid frame");
            }
            List<ColumnMeta> columns = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                columns.add(readColumn(in));
            }
            int rowCount = in.readInt();
            if (rowCount < 0) {
                throw new IOException("invalid frame");
            }
            List<Row> rows = new ArrayList<>(rowCount);
            for (int r = 0; r < rowCount; r++) {
                int size = in.readInt();
                if (size != columnCount) {
                    throw new IOException("invalid frame");
                }
                Object[] values = new Object[size];
                for (int c = 0; c < size; c++) {
                    values[c] = readValue(in);
                }
                rows.add(Row.of(columns, values));
            }
            result = SqlResult.query(columns, rows);
        } else {
            result = SqlResult.update(in.readInt());
        }
        StateView state = readState(in);
        return new ExecuteReply(result, state.schema(), state.transactionActive(), state.ownsTransaction());
    }

    public static byte[] encodeSchema(String schema) {
        return bytes(out -> writeUtf(out, schema == null ? "" : schema));
    }

    public static String decodeSchema(byte[] body) throws IOException {
        return readUtf(input(body));
    }

    public static byte[] encodeState(String schema, boolean transactionActive, boolean ownsTransaction) {
        return bytes(out -> writeState(out, schema, transactionActive, ownsTransaction));
    }

    public static StateView decodeState(byte[] body) throws IOException {
        return readState(input(body));
    }

    public static byte[] encodeNames(List<String> names) {
        return bytes(out -> {
            out.writeInt(names.size());
            for (String name : names) {
                writeUtf(out, name);
            }
        });
    }

    public static List<String> decodeNames(byte[] body) throws IOException {
        DataInputStream in = input(body);
        int count = in.readInt();
        if (count < 0) {
            throw new IOException("invalid frame");
        }
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add(readUtf(in));
        }
        return names;
    }

    public static byte[] encodeTables(List<TableDescription> tables) {
        return bytes(out -> {
            out.writeInt(tables.size());
            for (TableDescription table : tables) {
                writeTable(out, table);
            }
        });
    }

    public static List<TableDescription> decodeTables(byte[] body) throws IOException {
        DataInputStream in = input(body);
        int count = in.readInt();
        if (count < 0) {
            throw new IOException("invalid frame");
        }
        List<TableDescription> tables = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tables.add(readTable(in));
        }
        return tables;
    }

    public record BackupFile(String name, long size) {
    }

    public record BackupOffer(String databaseDirectory, List<BackupFile> files) {
    }

    public record BackupRead(int index, long offset, int length) {
    }

    public static byte[] encodeBackupRequest(boolean incremental) {
        return bytes(out -> out.writeByte(incremental ? 2 : 1));
    }

    public static boolean decodeBackupRequest(byte[] body) throws IOException {
        int kind = input(body).readUnsignedByte();
        if (kind == 1) {
            return false;
        }
        if (kind == 2) {
            return true;
        }
        throw new IOException("invalid frame");
    }

    public static byte[] encodeBackupOffer(String databaseDirectory, List<BackupFile> files) {
        List<BackupFile> list = files == null ? List.of() : files;
        return bytes(out -> {
            writeUtf(out, databaseDirectory == null ? "" : databaseDirectory);
            out.writeInt(list.size());
            for (BackupFile file : list) {
                writeUtf(out, file.name());
                out.writeLong(file.size());
            }
        });
    }

    public static BackupOffer decodeBackupOffer(byte[] body) throws IOException {
        DataInputStream in = input(body);
        String directory = readUtf(in);
        int count = in.readInt();
        if (count < 1 || count > 2) {
            throw new IOException("invalid frame");
        }
        List<BackupFile> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = readUtf(in);
            long size = in.readLong();
            if (size < 0) {
                throw new IOException("invalid frame");
            }
            files.add(new BackupFile(name, size));
        }
        return new BackupOffer(directory, files);
    }

    public static byte[] encodeBackupRead(int index, long offset, int length) {
        return bytes(out -> {
            out.writeInt(index);
            out.writeLong(offset);
            out.writeInt(length);
        });
    }

    public static BackupRead decodeBackupRead(byte[] body) throws IOException {
        DataInputStream in = input(body);
        int index = in.readInt();
        long offset = in.readLong();
        int length = in.readInt();
        if (index < 0 || offset < 0 || length < 0 || length > BackupPiece.CHUNK) {
            throw new IOException("invalid frame");
        }
        return new BackupRead(index, offset, length);
    }

    public static byte[] encodeError(String message) {
        return bytes(out -> writeUtf(out, message == null || message.isBlank() ? "server error" : message));
    }

    public static String decodeError(byte[] body) throws IOException {
        return readUtf(input(body));
    }

    private static void writeState(DataOutputStream out, String schema, boolean transactionActive, boolean ownsTransaction)
            throws IOException {
        writeUtf(out, schema == null ? "" : schema);
        out.writeBoolean(transactionActive);
        out.writeBoolean(ownsTransaction);
    }

    private static StateView readState(DataInputStream in) throws IOException {
        return new StateView(readUtf(in), in.readBoolean(), in.readBoolean());
    }

    private static void writeColumn(DataOutputStream out, ColumnMeta column) throws IOException {
        writeUtf(out, column.name());
        writeUtf(out, column.type().name());
        writeUtf(out, column.schema() == null ? "" : column.schema());
        writeUtf(out, column.table() == null ? "" : column.table());
    }

    private static ColumnMeta readColumn(DataInputStream in) throws IOException {
        String name = readUtf(in);
        DataType type = DataType.valueOf(readUtf(in));
        return new ColumnMeta(name, type, readUtf(in), readUtf(in));
    }

    private static void writeTable(DataOutputStream out, TableDescription table) throws IOException {
        writeUtf(out, table.schema());
        writeUtf(out, table.name());
        out.writeInt(table.columns().size());
        for (TableDescription.TableColumn column : table.columns()) {
            writeUtf(out, column.table());
            writeUtf(out, column.name());
            writeUtf(out, column.type().name());
            out.writeInt(column.length());
            out.writeInt(column.scale());
            out.writeBoolean(column.nullable());
            out.writeBoolean(column.autoIncrement());
            out.writeInt(column.primaryKeyPosition());
            out.writeBoolean(column.defaultSql() != null);
            if (column.defaultSql() != null) {
                writeUtf(out, column.defaultSql());
            }
        }
        out.writeInt(table.indexes().size());
        for (TableDescription.TableIndex index : table.indexes()) {
            writeUtf(out, index.table());
            writeUtf(out, index.name());
            out.writeBoolean(index.unique());
            out.writeInt(index.columns().size());
            for (String column : index.columns()) {
                writeUtf(out, column);
            }
            out.writeBoolean(index.descending());
        }
        writeUtf(out, table.primaryConstraint() == null ? "" : table.primaryConstraint());
        writeUtf(out, table.primaryIndex() == null ? "" : table.primaryIndex());
        out.writeBoolean(table.primaryDescending());
        out.writeInt(table.foreignKeys().size());
        for (TableDescription.ForeignKey key : table.foreignKeys()) {
            writeUtf(out, key.name());
            out.writeInt(key.columns().size());
            for (String column : key.columns()) {
                writeUtf(out, column);
            }
            writeUtf(out, key.referencedSchema());
            writeUtf(out, key.referencedTable());
            out.writeInt(key.referencedColumns().size());
            for (String column : key.referencedColumns()) {
                writeUtf(out, column);
            }
            writeUtf(out, key.indexName());
            out.writeBoolean(key.descending());
            writeUtf(out, key.onUpdate().name());
            writeUtf(out, key.onDelete().name());
        }
    }

    private static TableDescription readTable(DataInputStream in) throws IOException {
        String schema = readUtf(in);
        String name = readUtf(in);
        int columnCount = in.readInt();
        if (columnCount < 0) {
            throw new IOException("invalid frame");
        }
        List<TableDescription.TableColumn> columns = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            String table = readUtf(in);
            String column = readUtf(in);
            DataType type = DataType.valueOf(readUtf(in));
            int length = in.readInt();
            int scale = in.readInt();
            boolean nullable = in.readBoolean();
            boolean autoIncrement = in.readBoolean();
            int primaryKey = in.readInt();
            String defaultSql = in.readBoolean() ? readUtf(in) : null;
            columns.add(new TableDescription.TableColumn(
                    table, column, type, length, scale, nullable, autoIncrement, primaryKey, defaultSql));
        }
        int indexCount = in.readInt();
        if (indexCount < 0) {
            throw new IOException("invalid frame");
        }
        List<TableDescription.TableIndex> indexes = new ArrayList<>(indexCount);
        for (int i = 0; i < indexCount; i++) {
            String table = readUtf(in);
            String index = readUtf(in);
            boolean unique = in.readBoolean();
            int count = in.readInt();
            if (count < 0) {
                throw new IOException("invalid frame");
            }
            List<String> indexColumns = new ArrayList<>(count);
            for (int c = 0; c < count; c++) {
                indexColumns.add(readUtf(in));
            }
            boolean descending = in.readBoolean();
            indexes.add(new TableDescription.TableIndex(table, index, unique, indexColumns, descending));
        }
        String primaryConstraint = readUtf(in);
        String primaryIndex = readUtf(in);
        boolean primaryDescending = in.readBoolean();
        int foreignCount = in.readInt();
        if (foreignCount < 0) {
            throw new IOException("invalid frame");
        }
        List<TableDescription.ForeignKey> foreignKeys = new ArrayList<>(foreignCount);
        for (int i = 0; i < foreignCount; i++) {
            String foreignName = readUtf(in);
            int localCount = in.readInt();
            if (localCount < 0) {
                throw new IOException("invalid frame");
            }
            List<String> local = new ArrayList<>(localCount);
            for (int c = 0; c < localCount; c++) {
                local.add(readUtf(in));
            }
            String referencedSchema = readUtf(in);
            String referencedTable = readUtf(in);
            int referencedCount = in.readInt();
            if (referencedCount < 0) {
                throw new IOException("invalid frame");
            }
            List<String> referenced = new ArrayList<>(referencedCount);
            for (int c = 0; c < referencedCount; c++) {
                referenced.add(readUtf(in));
            }
            String indexName = readUtf(in);
            boolean descending = in.readBoolean();
            ReferentialAction onUpdate = readAction(in);
            ReferentialAction onDelete = readAction(in);
            foreignKeys.add(new TableDescription.ForeignKey(
                    foreignName, local, referencedSchema, referencedTable, referenced, indexName, descending,
                    onUpdate, onDelete));
        }
        return new TableDescription(schema, name, columns, indexes,
                primaryConstraint, primaryIndex, primaryDescending, foreignKeys);
    }

    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(V_NULL);
        } else if (value instanceof Boolean bool) {
            out.writeByte(V_BOOLEAN);
            out.writeBoolean(bool);
        } else if (value instanceof Byte number) {
            out.writeByte(V_BYTE);
            out.writeByte(number);
        } else if (value instanceof Short number) {
            out.writeByte(V_SHORT);
            out.writeShort(number);
        } else if (value instanceof Integer number) {
            out.writeByte(V_INT);
            out.writeInt(number);
        } else if (value instanceof Long number) {
            out.writeByte(V_LONG);
            out.writeLong(number);
        } else if (value instanceof Float number) {
            out.writeByte(V_FLOAT);
            out.writeFloat(number);
        } else if (value instanceof Double number) {
            out.writeByte(V_DOUBLE);
            out.writeDouble(number);
        } else if (value instanceof BigDecimal decimal) {
            out.writeByte(V_DECIMAL);
            writeUtf(out, decimal.toPlainString());
        } else if (value instanceof String text) {
            out.writeByte(V_STRING);
            writeUtf(out, text);
        } else if (value instanceof byte[] bytes) {
            out.writeByte(V_BYTES);
            out.writeInt(bytes.length);
            out.write(bytes);
        } else if (value instanceof LocalDate date) {
            out.writeByte(V_DATE);
            writeUtf(out, date.toString());
        } else if (value instanceof LocalTime time) {
            out.writeByte(V_TIME);
            writeUtf(out, time.toString());
        } else if (value instanceof LocalDateTime timestamp) {
            out.writeByte(V_TIMESTAMP);
            writeUtf(out, timestamp.toString());
        } else if (value instanceof java.sql.Date date) {
            out.writeByte(V_SQL_DATE);
            writeUtf(out, date.toLocalDate().toString());
        } else if (value instanceof java.sql.Time time) {
            out.writeByte(V_SQL_TIME);
            writeUtf(out, time.toLocalTime().toString());
        } else if (value instanceof java.sql.Timestamp timestamp) {
            out.writeByte(V_SQL_TIMESTAMP);
            writeUtf(out, timestamp.toLocalDateTime().toString());
        } else if (value instanceof Date date) {
            out.writeByte(V_UTIL_DATE);
            out.writeLong(date.getTime());
        } else {
            throw new SeaException("unsupported parameter type: " + value.getClass().getName());
        }
    }

    private static Object readValue(DataInputStream in) throws IOException {
        int tag = in.readUnsignedByte();
        return switch (tag) {
            case V_NULL -> null;
            case V_BOOLEAN -> in.readBoolean();
            case V_BYTE -> in.readByte();
            case V_SHORT -> in.readShort();
            case V_INT -> in.readInt();
            case V_LONG -> in.readLong();
            case V_FLOAT -> in.readFloat();
            case V_DOUBLE -> in.readDouble();
            case V_DECIMAL -> new BigDecimal(readUtf(in));
            case V_STRING -> readUtf(in);
            case V_BYTES -> {
                int length = in.readInt();
                if (length < 0 || length > MAX_FRAME) {
                    throw new IOException("invalid frame");
                }
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                yield bytes;
            }
            case V_DATE -> LocalDate.parse(readUtf(in));
            case V_TIME -> LocalTime.parse(readUtf(in));
            case V_TIMESTAMP -> LocalDateTime.parse(readUtf(in));
            case V_SQL_DATE -> java.sql.Date.valueOf(LocalDate.parse(readUtf(in)));
            case V_SQL_TIME -> java.sql.Time.valueOf(LocalTime.parse(readUtf(in)));
            case V_SQL_TIMESTAMP -> java.sql.Timestamp.valueOf(LocalDateTime.parse(readUtf(in)));
            case V_UTIL_DATE -> new Date(in.readLong());
            default -> throw new IOException("invalid frame");
        };
    }

    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) {
            throw new IOException("frame exceeds the limit");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static ReferentialAction readAction(DataInputStream in) throws IOException {
        String name = readUtf(in);
        try {
            return ReferentialAction.valueOf(name);
        } catch (IllegalArgumentException exception) {
            throw new IOException("unknown referential action: " + name);
        }
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME) {
            throw new IOException("invalid frame");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static DataInputStream input(byte[] body) {
        return new DataInputStream(new ByteArrayInputStream(body == null ? new byte[0] : body));
    }

    private static byte[] bytes(Writer writer) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            writer.write(out);
            out.flush();
            return buffer.toByteArray();
        } catch (SeaException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SeaException("failed to build the frame", exception);
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
