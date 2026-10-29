package io.github.gabrielmmoraes1999.seadb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Sequential record beside the {@code .db}. Each commit appends the
 * committed transaction and the page REDO, both compressed. The file
 * does not show the SQL or the values. The checkpoint marks how far the pages
 * follow the log, without erasing the history. The log returns to the header only on
 * backup: from then on it stores the transactions made afterward.
 */
final class TransactionLog implements AutoCloseable {

    /**
     * Crash test: after the REDO is on disk and before the pages are applied.
     */
    static volatile boolean crashAfterRedo;

    /** Crash test: the undo record is durable and the checkpoint has not written pages. */
    static volatile boolean crashAfterUndo;

    /** Crash test: checkpoint pages are on disk and the checkpoint record is not. */
    static volatile boolean crashAfterCheckpointPages;

    final Path path;
    private final FileChannel channel;
    private FileLock lock;
    private long logId;
    private int pageSize;
    private long epoch = 1;
    /**
     * End of the intact prefix. A torn record at the end is not included here.
     */
    private long consistentEnd;
    /** Next record offset, including bytes still sitting in {@link #pending}. */
    private volatile long logicalEnd;
    private final Object spillLock = new Object();
    private final Object drainLock = new Object();
    private byte[] pending = new byte[8192];
    private int pendingLength;

    private TransactionLog(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
        try {
            logicalEnd = channel.size();
        } catch (IOException exception) {
            throw new SeaException("failed to read the transaction log: " + exception.getMessage(), exception);
        }
    }

    static Path beside(Path database) {
        Path name = database.getFileName();
        String text = name == null ? "seadb.db" : name.toString();
        int dot = text.lastIndexOf('.');
        String base = dot > 0 ? text.substring(0, dot) : text;
        return database.resolveSibling(base + ".log");
    }

    static long newId() {
        long id = ThreadLocalRandom.current().nextLong();
        return id == 0L ? 1L : id;
    }

    static TransactionLog create(Path database, long logId, int pageSize, long epoch) {
        Path path = beside(database);
        try {
            if (Files.exists(path) && Files.size(path) > 0) {
                throw new SeaException("transaction log already exists");
            }
            if (Files.exists(path)) {
                Files.delete(path);
            }
            FileChannel channel = Format.openHeld(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            TransactionLog log = new TransactionLog(path, channel);
            try {
                log.lock();
                log.logId = logId;
                log.pageSize = pageSize;
                log.epoch = epoch;
                log.writeHeader();
                log.logicalEnd = Format.LOG_HEADER_SIZE;
                log.force();
                log.consistentEnd = Format.LOG_HEADER_SIZE;
                return log;
            } catch (RuntimeException exception) {
                log.close();
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // the incomplete file is left for the next attempt
                }
                throw exception;
            }
        } catch (SeaException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SeaException("could not create the transaction log: " + exception.getMessage(), exception);
        }
    }

    static TransactionLog open(Path database, long logId, int pageSize) {
        return openAt(beside(database), logId, pageSize, true);
    }

    static TransactionLog open(Path database, long logId, int pageSize, boolean applying) {
        return openAt(beside(database), logId, pageSize, applying);
    }

    /**
     * There is no log beside the database: the file does not exist or is empty,
     * even after resuming an interrupted copy.
     */
    static boolean missing(Path database) {
        return missing(database, true);
    }

    static boolean missing(Path database, boolean applying) {
        Path path = beside(database);
        try {
            if (applying) {
                absorbPending(path);
            } else if (Files.exists(pendingPath(path))) {
                throw new SeaException("checkpoint has an interrupted log apply; resume explicitly using -a");
            }
            return !Files.isRegularFile(path) || Files.size(path) == 0;
        } catch (IOException exception) {
            throw new SeaException("could not open the transaction log: " + exception.getMessage(), exception);
        }
    }

    /** Opens a standalone log, the one the applier will apply onto the database. */
    static TransactionLog openFile(Path logFile, long logId, int pageSize) {
        return openAt(logFile, logId, pageSize, false, true);
    }

    /** Opens the log only to read transactions, without requiring the database pair. */
    static TransactionLog inspect(Path logFile) {
        if (logFile == null) {
            throw new SeaException("transaction log is required");
        }
        return openAt(logFile, 0L, 0, false, false);
    }

    private static TransactionLog openAt(Path path, long logId, int pageSize, boolean absorb) {
        return openAt(path, logId, pageSize, absorb, true);
    }

    private static TransactionLog openAt(Path path, long logId, int pageSize, boolean absorb, boolean matchIdentity) {
        try {
            if (absorb) {
                absorbPending(path);
            }
            if (!Files.isRegularFile(path) || Files.size(path) == 0) {
                throw new SeaException("transaction log is missing");
            }
            FileChannel channel = Format.openHeld(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            TransactionLog log = new TransactionLog(path, channel);
            try {
                log.lock();
                log.checkHeader(logId, pageSize, matchIdentity);
                log.consistentEnd = channel.size();
                return log;
            } catch (RuntimeException exception) {
                log.close();
                throw exception;
            }
        } catch (SeaException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SeaException("could not open the transaction log: " + exception.getMessage(), exception);
        }
    }

    long epoch() {
        return epoch;
    }

    /**
     * The backup already copied the pair. The live log stores only what comes afterward.
     */
    void restart(long newEpoch) {
        epoch = newEpoch;
        writeHeader();
        force();
    }

    /** Updates the generation in the header and keeps the records already written. */
    void adoptEpoch(long newEpoch) {
        epoch = newEpoch;
        byte[] header = Format.readFully(channel, 0, Format.LOG_HEADER_SIZE);
        ByteBuffer.wrap(header).order(Format.ORDER).putLong(Format.LOG_EPOCH_AT, epoch);
        Format.writeFully(channel, 0, header);
        force();
    }

    /**
     * Replaces this log with the contents of {@code source}. A
     * {@code .log.apply} file beside it allows resume if the copy stops halfway.
     */
    void copyFrom(TransactionLog source) {
        Path pending = pendingPath(path);
        try {
            writeChannel(source.channel, source.size(), pending);
            installPending(pending);
            deleteQuiet(pending);
            consistentEnd = size();
            logicalEnd = consistentEnd;
            pendingLength = 0;
        } catch (IOException exception) {
            throw new SeaException("failed to copy the transaction log: " + exception.getMessage(), exception);
        }
    }

    void transferTo(Path target) {
        try {
            writeChannel(channel, size(), target);
        } catch (IOException exception) {
            throw new SeaException("failed to copy the transaction log: " + exception.getMessage(), exception);
        }
    }

    /** The first {@code length} bytes of the two logs are equal. */
    boolean matches(TransactionLog other, long length) {
        if (length < 0 || length > size() || length > other.size()) {
            return false;
        }
        long pos = 0;
        while (pos < length) {
            int want = (int) Math.min(1024 * 1024, length - pos);
            if (!Arrays.equals(Format.readFully(channel, pos, want), Format.readFully(other.channel, pos, want))) {
                return false;
            }
            pos += want;
        }
        return true;
    }

    long size() {
        try {
            return channel.size();
        } catch (IOException exception) {
            throw new SeaException("failed to read the transaction log: " + exception.getMessage(), exception);
        }
    }

    /** File size plus records accepted from inserts and not yet written. */
    long logicalLength() {
        return logicalEnd;
    }

    byte[] readAt(long offset, int length) {
        return Format.readFully(channel, offset, length);
    }

    long consistentEnd() {
        return consistentEnd;
    }

    /** Normal open validates only the terminal clean checkpoint, not the log history. */
    boolean cleanCheckpoint(long end) {
        if (end != size()) {
            return false;
        }
        if (end == Format.LOG_HEADER_SIZE) {
            return true;
        }
        // Both encodings have been used for checkpoints without active transactions.
        int[] lengths = {20 + pack(new byte[0]).length, 20 + pack(encodeActive(List.of())).length};
        for (int length : lengths) {
            long start = end - length;
            if (start < Format.LOG_HEADER_SIZE) {
                continue;
            }
            byte[] record = Format.readFully(channel, start, length);
            ByteBuffer frame = ByteBuffer.wrap(record).order(Format.ORDER);
            if (frame.getInt() != length || frame.getInt() != Format.RECORD_CHECKPOINT || frame.getLong() != start
                    || frame.getInt(length - 4) != Format.crc(record, 4, length - 8)) {
                continue;
            }
            if (decodeActive(readable(record)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * End offset of the last checkpoint record. The header size when the log has none.
     * A torn record at the end is ignored. A corrupt record before the end is rejected.
     */
    long lastCheckpoint() {
        long size = size();
        if (size < Format.LOG_HEADER_SIZE) {
            throw new SeaException("transaction log is corrupt");
        }
        long mark = Format.LOG_HEADER_SIZE;
        long at = Format.LOG_HEADER_SIZE;
        while (at + 4 <= size) {
            int recordLength = readInt(at);
            if (recordLength < 20 || at + recordLength > size) {
                break;
            }
            byte[] record = Format.readFully(channel, at, recordLength);
            int stored = ByteBuffer.wrap(record, recordLength - 4, 4).order(Format.ORDER).getInt();
            int expected = Format.crc(record, 4, recordLength - 8);
            if (stored != expected) {
                if (at + recordLength < size) {
                    throw new SeaException("transaction log is corrupt");
                }
                break;
            }
            int type = ByteBuffer.wrap(record, 4, 4).order(Format.ORDER).getInt();
            long lsn = ByteBuffer.wrap(record, 8, 8).order(Format.ORDER).getLong();
            if (lsn != at) {
                throw new SeaException("transaction log is corrupt");
            }
            if (type == Format.RECORD_CHECKPOINT) {
                mark = at + recordLength;
            } else if (!Format.knownLogRecord(type)) {
                throw new SeaException("transaction log is corrupt");
            }
            at += recordLength;
        }
        return mark;
    }

    void appendRedo(Collection<Span> spans, long fileSize) {
        ByteWriter body = new ByteWriter();
        int pieces = 0;
        boolean wrote = false;
        for (Span span : spans) {
            long remaining = span.length;
            long offset = span.offset;
            int dataAt = 0;
            while (remaining > 0) {
                int chunk = redoChunk(span, remaining, pieces == 0 ? 0 : body.size());
                if (pieces > 0 && body.size() + redoBytes(span, chunk) > CHUNK) {
                    writeRedo(fileSize, pieces, body);
                    wrote = true;
                    body = new ByteWriter();
                    pieces = 0;
                    chunk = redoChunk(span, remaining, 0);
                }
                body.u8(span.data == null ? 1 : 0);
                body.u64(offset);
                body.u32(chunk);
                if (span.data != null) {
                    body.raw(span.data, dataAt, chunk);
                    dataAt += chunk;
                }
                offset += chunk;
                remaining -= chunk;
                pieces++;
            }
        }
        if (pieces > 0 || !wrote) {
            writeRedo(fileSize, pieces, body);
        }
    }

    private void writeRedo(long fileSize, int pieces, ByteWriter body) {
        ByteWriter payload = new ByteWriter();
        payload.u64(fileSize);
        payload.u32(pieces);
        payload.raw(body.toArray(), 0, body.size());
        append(Format.RECORD_REDO, payload.toArray());
    }

    private static int redoBytes(Span span, int chunk) {
        return 13 + (span.data == null ? 0 : chunk);
    }

    private static int redoChunk(Span span, long remaining, int used) {
        int cap = span.data == null ? Integer.MAX_VALUE : Math.max(1, CHUNK - 13 - used);
        return (int) Math.min(remaining, cap);
    }

    void appendCheckpoint() {
        appendCheckpoint(List.of());
    }

    /**
     * An empty list keeps the historical checkpoint record, with no payload.
     * A non-empty list is the undo still required for transactions whose pages were stored.
     */
    void appendCheckpoint(List<TxnUndo> active) {
        if (active == null || active.isEmpty()) {
            append(Format.RECORD_CHECKPOINT, new byte[0]);
            return;
        }
        append(Format.RECORD_CHECKPOINT, encodeActive(active));
    }

    void appendUndo(TxnUndo undo) {
        append(Format.RECORD_UNDO, encodeUndo(undo));
    }

    void appendCommit(long txnId) {
        ByteWriter payload = new ByteWriter();
        payload.u64(txnId);
        append(Format.RECORD_COMMIT, payload.toArray());
    }

    void appendRollback(long txnId) {
        ByteWriter payload = new ByteWriter();
        payload.u64(txnId);
        append(Format.RECORD_ROLLBACK, payload.toArray());
    }

    /**
     * Transactions whose pages may contain uncommitted changes and that have no
     * later commit or rollback. The checkpoint record that ends at {@code checkpointLsn}
     * is included. Records after it replace or cancel that image.
     */
    List<TxnUndo> transactionsToUndo(long checkpointLsn) {
        long size = size();
        if (size < Format.LOG_HEADER_SIZE) {
            throw new SeaException("transaction log is corrupt");
        }
        Map<Long, TxnUndo> active = new LinkedHashMap<>();
        RecordReader reader = new RecordReader(size);
        long at = Format.LOG_HEADER_SIZE;
        while (at + 4 <= size) {
            int recordLength = reader.length(at);
            if (recordLength < 20 || at + recordLength > size) {
                break;
            }
            byte[] record = reader.read(at, recordLength);
            int stored = ByteBuffer.wrap(record, recordLength - 4, 4).order(Format.ORDER).getInt();
            int expected = Format.crc(record, 4, recordLength - 8);
            if (stored != expected) {
                if (at + recordLength < size) {
                    throw new SeaException("transaction log is corrupt");
                }
                break;
            }
            int type = ByteBuffer.wrap(record, 4, 4).order(Format.ORDER).getInt();
            long lsn = ByteBuffer.wrap(record, 8, 8).order(Format.ORDER).getLong();
            if (lsn != at) {
                throw new SeaException("transaction log is corrupt");
            }
            if (!Format.knownLogRecord(type)) {
                throw new SeaException("transaction log is corrupt");
            }
            long end = at + recordLength;
            boolean inForce = end == checkpointLsn || at >= checkpointLsn;
            if (inForce) {
                byte[] plain = readable(record);
                if (type == Format.RECORD_CHECKPOINT && end == checkpointLsn) {
                    active.clear();
                    for (TxnUndo undo : decodeActive(plain)) {
                        active.put(undo.id, undo);
                    }
                } else if (at >= checkpointLsn && type == Format.RECORD_CHECKPOINT) {
                    active.clear();
                    for (TxnUndo undo : decodeActive(plain)) {
                        active.put(undo.id, undo);
                    }
                } else if (at >= checkpointLsn && type == Format.RECORD_UNDO) {
                    TxnUndo undo = decodeUndo(plain);
                    active.put(undo.id, undo);
                } else if (at >= checkpointLsn && (type == Format.RECORD_COMMIT || type == Format.RECORD_ROLLBACK)) {
                    active.remove(readTxnId(plain));
                }
            }
            at += recordLength;
        }
        return List.copyOf(active.values());
    }

    /** Writes the SQL committed in this commit. The applier runs these statements. */
    void appendTransaction(List<Applied> statements) {
        if (statements == null || statements.isEmpty()) {
            return;
        }
        List<Applied> chunk = new ArrayList<>();
        int estimate = 4;
        for (Applied statement : statements) {
            int cost = statementCost(statement);
            if (!chunk.isEmpty() && estimate + cost > CHUNK) {
                writeTransaction(chunk);
                chunk.clear();
                estimate = 4;
            }
            chunk.add(statement);
            estimate += cost;
        }
        if (!chunk.isEmpty()) {
            writeTransaction(chunk);
        }
    }

    /** SQL of an open transaction. It becomes durable only when that id is committed. */
    void appendTxnSql(long txnId, List<Applied> statements) {
        if (statements == null || statements.isEmpty()) {
            return;
        }
        List<Applied> chunk = new ArrayList<>();
        int estimate = 12;
        for (Applied statement : statements) {
            int cost = statementCost(statement);
            if (!chunk.isEmpty() && estimate + cost > CHUNK) {
                writeTxnSql(txnId, chunk);
                chunk.clear();
                estimate = 12;
            }
            chunk.add(statement);
            estimate += cost;
        }
        if (!chunk.isEmpty()) {
            writeTxnSql(txnId, chunk);
        }
    }

    private void writeTxnSql(long txnId, List<Applied> statements) {
        ByteWriter payload = new ByteWriter();
        payload.u64(txnId);
        payload.u32(statements.size());
        for (Applied statement : statements) {
            payload.utf(statement.schema);
            payload.utf(statement.sql);
            Object[] params = statement.params == null ? new Object[0] : statement.params;
            payload.u32(params.length);
            for (Object param : params) {
                writeParam(payload, param);
            }
        }
        append(Format.RECORD_TXN_SQL, payload.toArray());
    }

    private void writeTransaction(List<Applied> statements) {
        ByteWriter payload = new ByteWriter();
        payload.u32(statements.size());
        for (Applied statement : statements) {
            payload.utf(statement.schema);
            payload.utf(statement.sql);
            Object[] params = statement.params == null ? new Object[0] : statement.params;
            payload.u32(params.length);
            for (Object param : params) {
                writeParam(payload, param);
            }
        }
        append(Format.RECORD_TRANSACTION, payload.toArray());
    }

    private static int statementCost(Applied statement) {
        int cost = 4 + utfCost(statement.schema) + utfCost(statement.sql);
        Object[] params = statement.params == null ? new Object[0] : statement.params;
        cost += 4;
        for (Object param : params) {
            cost += 16;
            if (param instanceof byte[] bytes) {
                cost += bytes.length;
            } else if (param instanceof String text) {
                cost += utfCost(text);
            }
        }
        return cost;
    }

    private static int utfCost(String text) {
        return 4 + (text == null ? 0 : text.length() * 3);
    }

    /**
     * Transactions whose record starts at {@code from} or later.
     * Each batch is a commit, with the record's ending offset.
     */
    List<Batch> readTransactions(long from) {
        return readTransactions(from, null);
    }

    /**
     * Transactions whose record starts at {@code from} or later.
     * {@code seen} receives the offset already read, so the export can show progress.
     */
    List<Batch> readTransactions(long from, LongConsumer seen) {
        List<Batch> batches = new ArrayList<>();
        try {
            forEachTransaction(from, seen, (batch, start) -> batches.add(batch));
        } catch (IOException exception) {
            throw new SeaException("could not read the transaction log: " + exception.getMessage(), exception);
        }
        return batches;
    }

    /**
     * Same commits as {@link #readTransactions(long, LongConsumer)}, one at a time.
     * {@code seen} receives the offset already read after each record.
     * {@code consumer} receives the commit and the offset where its record starts.
     */
    void forEachTransaction(long from, LongConsumer seen, TransactionConsumer consumer) throws IOException {
        long size = size();
        if (size < Format.LOG_HEADER_SIZE) {
            throw new SeaException("transaction log is corrupt");
        }
        List<Batch> held = new ArrayList<>();
        List<Long> heldAt = new ArrayList<>();
        Map<Long, List<Long>> openSql = new LinkedHashMap<>();
        long at = Format.LOG_HEADER_SIZE;
        while (at + 4 <= size) {
            int recordLength = readInt(at);
            if (recordLength < 20 || at + recordLength > size) {
                break;
            }
            byte[] record = Format.readFully(channel, at, recordLength);
            int stored = ByteBuffer.wrap(record, recordLength - 4, 4).order(Format.ORDER).getInt();
            int expected = Format.crc(record, 4, recordLength - 8);
            if (stored != expected) {
                if (at + recordLength < size) {
                    throw new SeaException("transaction log is corrupt");
                }
                break;
            }
            int type = ByteBuffer.wrap(record, 4, 4).order(Format.ORDER).getInt();
            long lsn = ByteBuffer.wrap(record, 8, 8).order(Format.ORDER).getLong();
            if (lsn != at) {
                throw new SeaException("transaction log is corrupt");
            }
            if (!Format.knownLogRecord(type)) {
                throw new SeaException("transaction log is corrupt");
            }
            if (type == Format.RECORD_TRANSACTION && at >= from) {
                held.add(new Batch(at + recordLength, decodeTransaction(readable(record))));
                heldAt.add(at);
            } else if (type == Format.RECORD_TXN_SQL && at >= from) {
                long id = txnSqlId(readable(record));
                openSql.computeIfAbsent(id, key -> new ArrayList<>()).add(at);
            } else if (type == Format.RECORD_COMMIT) {
                long id = readTxnId(readable(record));
                emitTxn(consumer, openSql.remove(id));
            } else if (type == Format.RECORD_ROLLBACK) {
                long id = readTxnId(readable(record));
                openSql.remove(id);
            } else if (type == Format.RECORD_REDO) {
                emitHeld(consumer, held, heldAt);
            }
            at += recordLength;
            if (seen != null) {
                seen.accept(at);
            }
        }
        if (seen != null) {
            seen.accept(size);
        }
    }

    private static void emitHeld(TransactionConsumer consumer, List<Batch> held, List<Long> heldAt) throws IOException {
        if (consumer != null) {
            for (int i = 0; i < held.size(); i++) {
                consumer.accept(held.get(i), heldAt.get(i));
            }
        }
        held.clear();
        heldAt.clear();
    }

    private void emitTxn(TransactionConsumer consumer, List<Long> starts) throws IOException {
        if (consumer == null || starts == null) {
            return;
        }
        for (long start : starts) {
            int recordLength = readInt(start);
            byte[] record = Format.readFully(channel, start, recordLength);
            consumer.accept(new Batch(start + recordLength, decodeTxnSql(readable(record))), start);
        }
    }

    /**
     * SQL of a commit that has no page redo after it. The pages are still only in memory,
     * so opening runs these statements. A redo record means the pages were already stored.
     */
    List<Batch> sqlToReplay(long from) {
        List<Batch> replay = new ArrayList<>();
        Map<Long, List<Long>> openSql = new LinkedHashMap<>();
        List<Long> staged = new ArrayList<>();
        long size = size();
        if (size < Format.LOG_HEADER_SIZE) {
            throw new SeaException("transaction log is corrupt");
        }
        RecordReader reader = new RecordReader(size);
        long at = Format.LOG_HEADER_SIZE;
        while (at + 4 <= size) {
            int recordLength = reader.length(at);
            if (recordLength < 20 || at + recordLength > size) {
                break;
            }
            byte[] record = reader.read(at, recordLength);
            int stored = ByteBuffer.wrap(record, recordLength - 4, 4).order(Format.ORDER).getInt();
            int expected = Format.crc(record, 4, recordLength - 8);
            if (stored != expected) {
                if (at + recordLength < size) {
                    throw new SeaException("transaction log is corrupt");
                }
                break;
            }
            int type = ByteBuffer.wrap(record, 4, 4).order(Format.ORDER).getInt();
            long lsn = ByteBuffer.wrap(record, 8, 8).order(Format.ORDER).getLong();
            if (lsn != at) {
                throw new SeaException("transaction log is corrupt");
            }
            if (!Format.knownLogRecord(type)) {
                throw new SeaException("transaction log is corrupt");
            }
            if (type == Format.RECORD_TXN_SQL && at >= from) {
                long id = txnSqlId(readable(record));
                openSql.computeIfAbsent(id, key -> new ArrayList<>()).add(at);
            } else if (type == Format.RECORD_COMMIT && at >= from) {
                long id = readTxnId(readable(record));
                List<Long> mine = openSql.remove(id);
                if (mine != null) {
                    staged.addAll(mine);
                }
            } else if (type == Format.RECORD_ROLLBACK && at >= from) {
                long id = readTxnId(readable(record));
                openSql.remove(id);
            } else if (type == Format.RECORD_REDO && at >= from) {
                staged.clear();
            }
            at += recordLength;
        }
        for (long start : staged) {
            int recordLength = readInt(start);
            byte[] record = Format.readFully(channel, start, recordLength);
            replay.add(new Batch(start + recordLength, decodeTxnSql(readable(record))));
        }
        return replay;
    }

    /**
     * Reapplies complete REDO records that start at {@code checkpointLsn} or later.
     * An incomplete record or one with an invalid CRC at the end of the file is discarded.
     */
    boolean replay(long checkpointLsn, BiConsumer<Long, List<Span>> redo) {
        return replay(checkpointLsn, redo, null);
    }

    boolean replay(long checkpointLsn, BiConsumer<Long, List<Span>> redo, LongConsumer checkpointRead) {
        long size = size();
        consistentEnd = Math.min(size, Format.LOG_HEADER_SIZE);
        if (size < Format.LOG_HEADER_SIZE) {
            throw new SeaException("transaction log is corrupt");
        }
        RecordReader reader = new RecordReader(size);
        long at = Format.LOG_HEADER_SIZE;
        boolean applied = false;
        while (at + 4 <= size) {
            int recordLength = reader.length(at);
            if (recordLength < 20 || at + recordLength > size) {
                consistentEnd = at;
                break;
            }
            byte[] record = reader.read(at, recordLength);
            int stored = ByteBuffer.wrap(record, recordLength - 4, 4).order(Format.ORDER).getInt();
            int expected = Format.crc(record, 4, recordLength - 8);
            if (stored != expected) {
                if (at + recordLength < size) {
                    throw new SeaException("transaction log is corrupt");
                }
                consistentEnd = at;
                break;
            }
            int type = ByteBuffer.wrap(record, 4, 4).order(Format.ORDER).getInt();
            long lsn = ByteBuffer.wrap(record, 8, 8).order(Format.ORDER).getLong();
            if (lsn != at) {
                throw new SeaException("transaction log is corrupt");
            }
            if (at >= checkpointLsn && type == Format.RECORD_REDO) {
                byte[] plain = readable(record);
                redo.accept(fileSizeOf(plain), spansOf(plain));
                applied = true;
            } else if (!Format.knownLogRecord(type)) {
                throw new SeaException("transaction log is corrupt");
            }
            at += recordLength;
            consistentEnd = at;
            if (type == Format.RECORD_CHECKPOINT && at >= checkpointLsn && checkpointRead != null) {
                checkpointRead.accept(at);
            }
        }
        return applied;
    }

    void force() {
        drain();
        try {
            channel.force(true);
        } catch (IOException exception) {
            throw new SeaException("failed to sync the transaction log: " + exception.getMessage(), exception);
        }
    }

    /**
     * Writes buffered records. An insert only copies into the buffer, so it does
     * not wait while this syncs the file.
     */
    void drain() {
        synchronized (drainLock) {
            byte[] batch;
            int length;
            long at;
            synchronized (spillLock) {
                if (pendingLength == 0) {
                    return;
                }
                batch = pending;
                length = pendingLength;
                at = logicalEnd - length;
                pending = new byte[Math.max(8192, Math.min(length, 1024 * 1024))];
                pendingLength = 0;
            }
            Format.writeFully(channel, at, length == batch.length ? batch : Arrays.copyOf(batch, length));
        }
    }

    void truncate(long size) {
        synchronized (drainLock) {
            synchronized (spillLock) {
                pendingLength = 0;
                logicalEnd = size;
            }
            try {
                if (channel.size() != size) {
                    channel.truncate(size);
                }
                consistentEnd = size;
            } catch (IOException exception) {
                throw new SeaException("failed to resize the transaction log: " + exception.getMessage(), exception);
            }
        }
    }

    @Override
    public void close() {
        try {
            drain();
        } catch (RuntimeException ignored) {
            // a failed log still has to release the file
        }
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException ignored) {
            // the channel is closed next
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // the lock ends when the process releases the file
        }
        lock = null;
    }

    private void append(int type, byte[] payload) {
        appendPacked(type, pack(payload));
    }

    /** Appends a payload already compressed by {@link #pack(byte[])}. The offset is filled in here. */
    void appendPacked(int type, byte[] packed) {
        synchronized (spillLock) {
            long lsn = logicalEnd;
            byte[] body = new byte[12 + packed.length];
            ByteBuffer buffer = ByteBuffer.wrap(body).order(Format.ORDER);
            buffer.putInt(type);
            buffer.putLong(lsn);
            System.arraycopy(packed, 0, body, 12, packed.length);
            int crc = Format.crc(body, 0, body.length);
            byte[] record = new byte[4 + body.length + 4];
            ByteBuffer frame = ByteBuffer.wrap(record).order(Format.ORDER);
            frame.putInt(record.length);
            frame.put(body);
            frame.putInt(crc);
            int needed = pendingLength + record.length;
            if (needed > pending.length) {
                pending = Arrays.copyOf(pending, Math.max(needed, pending.length * 2));
            }
            System.arraycopy(record, 0, pending, pendingLength, record.length);
            pendingLength = needed;
            logicalEnd = lsn + record.length;
        }
    }

    byte[] packedUndo(TxnUndo undo) {
        return pack(encodeUndo(undo));
    }

    byte[] packedActive(List<TxnUndo> active) {
        return pack(encodeActive(active));
    }

    private static final int MAX_PLAIN = 256 * 1024 * 1024;
    /** One log record stays under this so a bulk commit does not build a single byte array. */
    private static final int CHUNK = 32 * 1024 * 1024;

    /** Compresses the record body and scrambles the bytes so the file does not expose the text. */
    private static byte[] pack(byte[] payload) {
        byte[] compressed = deflate(payload);
        byte[] packed = new byte[4 + compressed.length];
        ByteBuffer.wrap(packed).order(Format.ORDER).putInt(payload.length);
        System.arraycopy(compressed, 0, packed, 4, compressed.length);
        mask(packed);
        return packed;
    }

    private static byte[] readable(byte[] record) {
        if (record.length < 20) {
            throw new SeaException("transaction log is corrupt");
        }
        byte[] plain = unpack(Arrays.copyOfRange(record, 16, record.length - 4));
        byte[] logical = new byte[20 + plain.length];
        System.arraycopy(record, 4, logical, 4, 12);
        System.arraycopy(plain, 0, logical, 16, plain.length);
        ByteBuffer.wrap(logical).order(Format.ORDER).putInt(logical.length);
        return logical;
    }

    private static byte[] unpack(byte[] packed) {
        if (packed.length < 4) {
            throw new SeaException("transaction log is corrupt");
        }
        byte[] copy = packed.clone();
        mask(copy);
        int rawLength = ByteBuffer.wrap(copy, 0, 4).order(Format.ORDER).getInt();
        if (rawLength < 0 || rawLength > MAX_PLAIN) {
            throw new SeaException("transaction log is corrupt");
        }
        if (rawLength == 0) {
            return new byte[0];
        }
        try {
            byte[] plain = inflate(Arrays.copyOfRange(copy, 4, copy.length), rawLength);
            if (plain.length != rawLength) {
                throw new SeaException("transaction log is corrupt");
            }
            return plain;
        } catch (DataFormatException exception) {
            throw new SeaException("transaction log is corrupt");
        }
    }

    private static void mask(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] ^= (byte) (0xA5 ^ (i * 31));
        }
    }

    /**
     * Fast compression. Maximum effort spent more time on a bulk commit than writing
     * the pages, and the redo did not come out smaller.
     */
    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] compressed, int rawLength) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, rawLength));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    throw new DataFormatException("incomplete block");
                }
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    throw new DataFormatException("incomplete block");
                }
                if (out.size() > MAX_PLAIN - count) {
                    throw new DataFormatException("block is too large");
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private void writeHeader() {
        byte[] header = new byte[Format.LOG_HEADER_SIZE];
        System.arraycopy(Format.LOG_MAGIC, 0, header, 0, Format.LOG_MAGIC.length);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        buffer.putShort(Format.VERSION_OFFSET, (short) Format.LOG_VERSION);
        buffer.putLong(Format.LOG_ID_AT, logId);
        buffer.putInt(Format.LOG_PAGE_AT, pageSize);
        buffer.putLong(Format.LOG_EPOCH_AT, epoch);
        Format.writeFully(channel, 0, header);
        truncate(Format.LOG_HEADER_SIZE);
    }

    private void checkHeader(long expectedId, int expectedPage, boolean matchIdentity) {
        if (size() < Format.LOG_HEADER_SIZE) {
            throw new SeaException("transaction log is missing");
        }
        byte[] header = Format.readFully(channel, 0, Format.LOG_HEADER_SIZE);
        if (!Arrays.equals(header, 0, Format.LOG_MAGIC.length, Format.LOG_MAGIC, 0, Format.LOG_MAGIC.length)) {
            throw new SeaException(matchIdentity ? "log does not belong to this database" : "file is not a transaction log");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        int version = buffer.getShort(Format.VERSION_OFFSET) & 0xFFFF;
        if (version != Format.LOG_VERSION) {
            throw new SeaException("unsupported log version: " + version);
        }
        logId = buffer.getLong(Format.LOG_ID_AT);
        pageSize = buffer.getInt(Format.LOG_PAGE_AT);
        epoch = buffer.getLong(Format.LOG_EPOCH_AT);
        if (matchIdentity && (logId != expectedId || pageSize != expectedPage)) {
            throw new SeaException("log does not belong to this database");
        }
    }

    private void readIdentity() {
        byte[] header = Format.readFully(channel, 0, Format.LOG_HEADER_SIZE);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        logId = buffer.getLong(Format.LOG_ID_AT);
        pageSize = buffer.getInt(Format.LOG_PAGE_AT);
        epoch = buffer.getLong(Format.LOG_EPOCH_AT);
    }

    private void installPending(Path pending) throws IOException {
        long size = Files.size(pending);
        try (FileChannel in = FileChannel.open(pending, StandardOpenOption.READ)) {
            truncate(0);
            long pos = 0;
            while (pos < size) {
                int want = (int) Math.min(1024 * 1024, size - pos);
                byte[] data = Format.readFully(in, pos, want);
                Format.writeFully(channel, pos, data);
                pos += data.length;
            }
            force();
            readIdentity();
        }
    }

    /**
     * Finishes an interrupted copy. If the live log is already larger, the pending
     * copy fell behind and can be removed.
     */
    private static void absorbPending(Path path) throws IOException {
        Path pending = pendingPath(path);
        if (!Files.isRegularFile(pending) || Files.size(pending) < Format.LOG_HEADER_SIZE) {
            return;
        }
        if (Files.isRegularFile(path) && Files.size(path) >= Files.size(pending)) {
            deleteQuiet(pending);
            return;
        }
        long size = Files.size(pending);
        try (FileChannel in = FileChannel.open(pending, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            out.truncate(0);
            long pos = 0;
            while (pos < size) {
                int want = (int) Math.min(1024 * 1024, size - pos);
                byte[] data = Format.readFully(in, pos, want);
                Format.writeFully(out, pos, data);
                pos += data.length;
            }
            out.force(true);
        }
        deleteQuiet(pending);
    }

    private static void writeChannel(FileChannel source, long size, Path target) throws IOException {
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long pos = 0;
            while (pos < size) {
                int want = (int) Math.min(1024 * 1024, size - pos);
                byte[] data = Format.readFully(source, pos, want);
                Format.writeFully(out, pos, data);
                pos += data.length;
            }
            out.force(true);
        }
    }

    private static Path pendingPath(Path path) {
        return Path.of(path.toString() + ".apply");
    }

    private static void deleteQuiet(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the next open resumes what was left
        }
    }

    private int readInt(long offset) {
        byte[] data = Format.readFully(channel, offset, 4);
        return ByteBuffer.wrap(data).order(Format.ORDER).getInt();
    }

    /** Read-ahead for recovery scans, without a file mapping or retained log history. */
    private final class RecordReader {
        private final long end;
        private final byte[] window = new byte[1024 * 1024];
        private long start = -1;
        private int available;

        RecordReader(long end) {
            this.end = end;
        }

        int length(long offset) {
            fill(offset, Integer.BYTES);
            return ByteBuffer.wrap(window, (int) (offset - start), Integer.BYTES).order(Format.ORDER).getInt();
        }

        byte[] read(long offset, int length) {
            if (length > window.length) {
                return Format.readFully(channel, offset, length);
            }
            fill(offset, length);
            int at = (int) (offset - start);
            return Arrays.copyOfRange(window, at, at + length);
        }

        private void fill(long offset, int needed) {
            if (offset >= start && offset - start <= (long) available - needed) {
                return;
            }
            int count = (int) Math.min(window.length, end - offset);
            ByteBuffer buffer = ByteBuffer.wrap(window, 0, count);
            long position = offset;
            try {
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer, position);
                    if (read < 0) {
                        throw new SeaException("transaction log is truncated");
                    }
                    position += read;
                }
            } catch (IOException exception) {
                throw new SeaException("could not read the transaction log: " + exception.getMessage(), exception);
            }
            start = offset;
            available = count;
        }
    }

    private static long fileSizeOf(byte[] record) {
        return ByteBuffer.wrap(record, 16, 8).order(Format.ORDER).getLong();
    }

    private static List<Span> spansOf(byte[] record) {
        int payloadEnd = record.length - 4;
        ByteBuffer buffer = ByteBuffer.wrap(record, 16, payloadEnd - 16).order(Format.ORDER);
        long fileSize = buffer.getLong();
        int count = buffer.getInt();
        if (fileSize < 0 || count < 0) {
            throw new SeaException("transaction log is corrupt");
        }
        List<Span> spans = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 13) {
                throw new SeaException("transaction log is corrupt");
            }
            int kind = buffer.get() & 0xFF;
            long offset = buffer.getLong();
            int length = buffer.getInt();
            if ((kind != 0 && kind != 1) || offset < 0 || length < 0) {
                throw new SeaException("transaction log is corrupt");
            }
            byte[] data = null;
            if (kind == 0) {
                if (buffer.remaining() < length) {
                    throw new SeaException("transaction log is corrupt");
                }
                data = new byte[length];
                buffer.get(data);
            }
            spans.add(new Span(offset, length, data));
        }
        if (buffer.remaining() != 0) {
            throw new SeaException("transaction log is corrupt");
        }
        return spans;
    }

    private void lock() {
        try {
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                lock = null;
            }
            if (lock == null) {
                throw new SeaException("database is already open");
            }
        } catch (SeaException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SeaException("could not lock the transaction log: " + exception.getMessage(), exception);
        }
    }

    static final class Span {
        final long offset;
        final long length;
        final byte[] data;

        Span(long offset, long length, byte[] data) {
            this.offset = offset;
            this.length = length;
            this.data = data;
        }

        long end() {
            return offset + length;
        }

        Span slice(long from, long to) {
            long size = to - from;
            if (data == null) {
                return new Span(from, size, null);
            }
            int start = (int) (from - offset);
            byte[] copy = new byte[(int) size];
            System.arraycopy(data, start, copy, 0, copy.length);
            return new Span(from, size, copy);
        }
    }

    private static final class ByteWriter {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        int size() {
            return out.size();
        }

        void u8(int value) {
            out.write(value & 0xFF);
        }

        void u32(int value) {
            out.write((value >>> 24) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        void u64(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (value >>> shift) & 0xFF);
            }
        }

        void raw(byte[] data, int offset, int length) {
            out.write(data, offset, length);
        }

        void utf(String text) {
            byte[] data = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            u32(data.length);
            raw(data, 0, data.length);
        }

        byte[] toArray() {
            return out.toByteArray();
        }
    }

    /** One row as it was before the transaction changed it. */
    static final class RowImage {
        final long id;
        final Object[] values;

        RowImage(long id, Object[] values) {
            this.id = id;
            this.values = values == null ? new Object[0] : values;
        }
    }

    /** Net undo of one table: inserted ids disappear, and the other rows return to these values. */
    static final class TableUndo {
        final String schema;
        final String table;
        final long[] inserted;
        final List<RowImage> updated;
        final List<RowImage> deleted;

        TableUndo(String schema, String table, long[] inserted, List<RowImage> updated, List<RowImage> deleted) {
            this.schema = schema;
            this.table = table;
            this.inserted = inserted == null ? new long[0] : inserted;
            this.updated = updated == null ? List.of() : updated;
            this.deleted = deleted == null ? List.of() : deleted;
        }

        boolean empty() {
            return inserted.length == 0 && updated.isEmpty() && deleted.isEmpty();
        }
    }

    /** Undo image of one transaction, including tables it created. */
    static final class TxnUndo {
        final long id;
        final List<String[]> created;
        final List<TableUndo> tables;

        TxnUndo(long id, List<String[]> created, List<TableUndo> tables) {
            this.id = id;
            this.created = created == null ? List.of() : created;
            this.tables = tables == null ? List.of() : tables;
        }

        boolean empty() {
            if (!created.isEmpty()) {
                return false;
            }
            for (TableUndo table : tables) {
                if (!table.empty()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static byte[] encodeActive(List<TxnUndo> active) {
        ByteWriter payload = new ByteWriter();
        payload.u32(active.size());
        for (TxnUndo undo : active) {
            writeUndo(payload, undo);
        }
        return payload.toArray();
    }

    private static byte[] encodeUndo(TxnUndo undo) {
        ByteWriter payload = new ByteWriter();
        writeUndo(payload, undo);
        return payload.toArray();
    }

    private static void writeUndo(ByteWriter payload, TxnUndo undo) {
        payload.u64(undo.id);
        payload.u32(undo.created.size());
        for (String[] created : undo.created) {
            payload.utf(created[0]);
            payload.utf(created[1]);
        }
        payload.u32(undo.tables.size());
        for (TableUndo table : undo.tables) {
            payload.utf(table.schema);
            payload.utf(table.table);
            payload.u32(table.inserted.length);
            for (long id : table.inserted) {
                payload.u64(id);
            }
            writeRows(payload, table.updated);
            writeRows(payload, table.deleted);
        }
    }

    private static void writeRows(ByteWriter payload, List<RowImage> rows) {
        payload.u32(rows.size());
        for (RowImage row : rows) {
            payload.u64(row.id);
            payload.u32(row.values.length);
            for (Object value : row.values) {
                writeParam(payload, value);
            }
        }
    }

    private static List<TxnUndo> decodeActive(byte[] record) {
        ByteBuffer buffer = payload(record);
        if (buffer.remaining() == 0) {
            return List.of();
        }
        int count = readInt(buffer);
        if (count < 0) {
            throw new SeaException("transaction log is corrupt");
        }
        List<TxnUndo> undos = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            undos.add(readUndo(buffer));
        }
        if (buffer.remaining() != 0) {
            throw new SeaException("transaction log is corrupt");
        }
        return undos;
    }

    private static TxnUndo decodeUndo(byte[] record) {
        ByteBuffer buffer = payload(record);
        TxnUndo undo = readUndo(buffer);
        if (buffer.remaining() != 0) {
            throw new SeaException("transaction log is corrupt");
        }
        return undo;
    }

    private static long readTxnId(byte[] record) {
        ByteBuffer buffer = payload(record);
        long id = readLong(buffer);
        if (buffer.remaining() != 0) {
            throw new SeaException("transaction log is corrupt");
        }
        return id;
    }

    private static ByteBuffer payload(byte[] record) {
        if (record.length < 20) {
            throw new SeaException("transaction log is corrupt");
        }
        return ByteBuffer.wrap(record, 16, record.length - 20).order(Format.ORDER);
    }

    private static TxnUndo readUndo(ByteBuffer buffer) {
        long id = readLong(buffer);
        int createdCount = readInt(buffer);
        if (createdCount < 0) {
            throw new SeaException("transaction log is corrupt");
        }
        List<String[]> created = new ArrayList<>(createdCount);
        for (int i = 0; i < createdCount; i++) {
            created.add(new String[]{readUtf(buffer), SystemTables.currentName(readUtf(buffer))});
        }
        int tableCount = readInt(buffer);
        if (tableCount < 0) {
            throw new SeaException("transaction log is corrupt");
        }
        List<TableUndo> tables = new ArrayList<>(tableCount);
        for (int i = 0; i < tableCount; i++) {
            String schema = readUtf(buffer);
            String table = SystemTables.currentName(readUtf(buffer));
            int insertedCount = readInt(buffer);
            if (insertedCount < 0) {
                throw new SeaException("transaction log is corrupt");
            }
            long[] inserted = new long[insertedCount];
            for (int n = 0; n < insertedCount; n++) {
                inserted[n] = readLong(buffer);
            }
            tables.add(new TableUndo(schema, table, inserted, readRows(buffer), readRows(buffer)));
        }
        return new TxnUndo(id, created, tables);
    }

    private static List<RowImage> readRows(ByteBuffer buffer) {
        int count = readInt(buffer);
        if (count < 0) {
            throw new SeaException("transaction log is corrupt");
        }
        List<RowImage> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long id = readLong(buffer);
            int columns = readInt(buffer);
            if (columns < 0) {
                throw new SeaException("transaction log is corrupt");
            }
            Object[] values = new Object[columns];
            for (int c = 0; c < columns; c++) {
                values[c] = readParam(buffer);
            }
            rows.add(new RowImage(id, values));
        }
        return rows;
    }

    /** A committed SQL statement, with the session schema and the parameters. */
    static final class Applied {
        final String schema;
        final String sql;
        final Object[] params;

        Applied(String schema, String sql, Object[] params) {
            this.schema = schema == null || schema.isBlank() ? "public" : schema;
            this.sql = sql;
            this.params = params == null ? new Object[0] : params;
        }
    }

    /** Receives one commit while the log is still being read. */
    @FunctionalInterface
    interface TransactionConsumer {
        void accept(Batch batch, long start) throws IOException;
    }

    /** A log commit: the statements and the offset where the record ends. */
    static final class Batch {
        final long end;
        final List<Applied> statements;

        Batch(long end, List<Applied> statements) {
            this.end = end;
            this.statements = statements;
        }
    }

    private static void writeParam(ByteWriter out, Object value) {
        if (value == null) {
            out.u8(0);
        } else if (value instanceof Boolean bool) {
            out.u8(1);
            out.u8(bool ? 1 : 0);
        } else if (value instanceof Integer number) {
            out.u8(2);
            out.u32(number);
        } else if (value instanceof Long number) {
            out.u8(3);
            out.u64(number);
        } else if (value instanceof Double number) {
            out.u8(4);
            out.u64(Double.doubleToRawLongBits(number));
        } else if (value instanceof Float number) {
            out.u8(5);
            out.u32(Float.floatToRawIntBits(number));
        } else if (value instanceof String text) {
            out.u8(6);
            out.utf(text);
        } else if (value instanceof byte[] bytes) {
            out.u8(7);
            out.u32(bytes.length);
            out.raw(bytes, 0, bytes.length);
        } else if (value instanceof BigDecimal decimal) {
            out.u8(8);
            out.utf(decimal.toPlainString());
        } else if (value instanceof LocalDate date) {
            out.u8(9);
            out.utf(date.toString());
        } else if (value instanceof LocalTime time) {
            out.u8(10);
            out.utf(time.toString());
        } else if (value instanceof LocalDateTime time) {
            out.u8(11);
            out.utf(time.toString());
        } else if (value instanceof java.sql.Date date) {
            out.u8(14);
            out.utf(date.toLocalDate().toString());
        } else if (value instanceof java.sql.Time time) {
            out.u8(15);
            out.utf(time.toLocalTime().toString());
        } else if (value instanceof java.sql.Timestamp time) {
            out.u8(16);
            out.utf(time.toLocalDateTime().toString());
        } else if (value instanceof java.util.Date date) {
            out.u8(17);
            out.u64(date.getTime());
        } else if (value instanceof Short number) {
            out.u8(12);
            out.u32(number);
        } else if (value instanceof Byte number) {
            out.u8(13);
            out.u32(number);
        } else {
            throw new SeaException("unsupported parameter type in the log: " + value.getClass().getName());
        }
    }

    private static long txnSqlId(byte[] logical) {
        return readLong(payload(logical));
    }

    private static List<Applied> decodeTxnSql(byte[] logical) {
        ByteBuffer buffer = payload(logical);
        readLong(buffer);
        List<Applied> statements = readApplied(buffer);
        if (buffer.remaining() != 0) {
            throw new SeaException("transaction log is corrupt");
        }
        return statements;
    }

    private static List<Applied> decodeTransaction(byte[] record) {
        ByteBuffer buffer = ByteBuffer.wrap(record, 16, record.length - 20).order(Format.ORDER);
        List<Applied> statements = readApplied(buffer);
        if (buffer.remaining() != 0) {
            throw new SeaException("transaction log is corrupt");
        }
        return statements;
    }

    private static List<Applied> readApplied(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            throw new SeaException("transaction log is corrupt");
        }
        int count = buffer.getInt();
        if (count < 0) {
            throw new SeaException("transaction log is corrupt");
        }
        List<Applied> statements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String schema = readUtf(buffer);
            String sql = readUtf(buffer);
            if (buffer.remaining() < 4) {
                throw new SeaException("transaction log is corrupt");
            }
            int params = buffer.getInt();
            if (params < 0) {
                throw new SeaException("transaction log is corrupt");
            }
            Object[] values = new Object[params];
            for (int p = 0; p < params; p++) {
                values[p] = readParam(buffer);
            }
            statements.add(new Applied(schema, sql, values));
        }
        return statements;
    }

    private static Object readParam(ByteBuffer buffer) {
        if (buffer.remaining() < 1) {
            throw new SeaException("transaction log is corrupt");
        }
        int tag = buffer.get() & 0xFF;
        return switch (tag) {
            case 0 -> null;
            case 1 -> readByte(buffer) != 0;
            case 2 -> readInt(buffer);
            case 3 -> readLong(buffer);
            case 4 -> Double.longBitsToDouble(readLong(buffer));
            case 5 -> Float.intBitsToFloat(readInt(buffer));
            case 6 -> readUtf(buffer);
            case 7 -> {
                int length = readInt(buffer);
                if (length < 0 || buffer.remaining() < length) {
                    throw new SeaException("transaction log is corrupt");
                }
                byte[] data = new byte[length];
                buffer.get(data);
                yield data;
            }
            case 8 -> new BigDecimal(readUtf(buffer));
            case 9 -> LocalDate.parse(readUtf(buffer));
            case 10 -> LocalTime.parse(readUtf(buffer));
            case 11 -> LocalDateTime.parse(readUtf(buffer));
            case 12 -> (short) readInt(buffer);
            case 13 -> (byte) readInt(buffer);
            case 14 -> java.sql.Date.valueOf(LocalDate.parse(readUtf(buffer)));
            case 15 -> java.sql.Time.valueOf(LocalTime.parse(readUtf(buffer)));
            case 16 -> java.sql.Timestamp.valueOf(LocalDateTime.parse(readUtf(buffer)));
            case 17 -> new java.util.Date(readLong(buffer));
            default -> throw new SeaException("transaction log is corrupt");
        };
    }

    private static int readByte(ByteBuffer buffer) {
        if (buffer.remaining() < 1) {
            throw new SeaException("transaction log is corrupt");
        }
        return buffer.get() & 0xFF;
    }

    private static int readInt(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            throw new SeaException("transaction log is corrupt");
        }
        return buffer.getInt();
    }

    private static long readLong(ByteBuffer buffer) {
        if (buffer.remaining() < 8) {
            throw new SeaException("transaction log is corrupt");
        }
        return buffer.getLong();
    }

    private static String readUtf(ByteBuffer buffer) {
        int length = readInt(buffer);
        if (length < 0 || buffer.remaining() < length) {
            throw new SeaException("transaction log is corrupt");
        }
        byte[] data = new byte[length];
        buffer.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }
}
