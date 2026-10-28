package io.github.gabrielmmoraes1999.nexora;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code .db} file. Page 0 is the header. The catalog and each table
 * occupy whole pages. The {@code .log} beside it stores each commit since
 * creation or the last full backup. The checkpoint syncs the pages and
 * marks how far they follow that log. Without the file, opening writes
 * a new log, with only the header, advances the generation, and continues from the
 * pages already stored. The log from the previous generation can no longer open the database.
 * Starting writes a checkpoint into the database and into the log. Stopping writes it again.
 * A checkpoint may store pages of a transaction that is still open. The undo record is forced
 * first, and explicit log application undoes that transaction. Normal open refuses pending recovery.
 * Writing the page does not commit it.
 * The checkpoint uses the same lock as SQL. Two open transactions cannot change the same table.
 * A large before-image is stored whole in the log, and the log is not cut until a full backup.
 * A successful explicit apply synchronizes the checkpoint before normal opening is allowed.
 * A rejected log leaves the database checkpoint unchanged.
 */
final class DatabaseFile implements AutoCloseable {

    final Path path;
    final FileChannel channel;
    private FileLock lock;
    private TransactionLog log;
    private long logId;
    private long epoch = 1;
    private long appliedOffset;
    private final Object durableLock = new Object();
    private volatile long checkpointLsn = Format.LOG_HEADER_SIZE;
    private volatile long deferredCheckpointLsn = -1;
    private boolean undoForceDeferred;
    private boolean deferDbSync;
    private ArrayList<DeferredIo> capturing;
    private volatile List<DeferredIo> deferredView = List.of();
    private boolean stable;
    /** Undo of transactions whose uncommitted pages were stored by the last checkpoint. */
    private List<TransactionLog.TxnUndo> activeUndo = List.of();
    private boolean undoPending;
    private List<TransactionLog.TxnUndo> pendingUndos = List.of();
    /** Committed SQL whose pages were not stored. Opening runs it once. */
    private List<TransactionLog.Batch> sqlReplay = List.of();
    /** Undo and checkpoint bodies compressed while inserts were still running. */
    private List<byte[]> packedUndos;
    private byte[] packedCheckpoint;
    private CommitBuffer commit;
    private boolean crashed;
    private boolean recoveryPending;
    private boolean ready;
    private boolean appliedToDisk;
    /** Apply writes pages and may record the log's own checkpoint. It does not append one. */
    private boolean applying;
    /** A normal open writes a checkpoint on the way in and on the way out. */
    private boolean sessionBoundary;
    private long fileSize;
    private long catalogOffset;
    private int catalogLength;
    private long generation;
    private int activeSlot;
    private int pageSize;
    private long catalogSpan;
    private List<Extent> freeExtents = new ArrayList<>();
    private List<StoredTable> tables = List.of();
    private final List<Extent> retire = new ArrayList<>();
    private List<Extent> wipeAfter = List.of();
    private final Cache cache;
    private boolean backupActive;
    private boolean backupFull;
    private List<BackupSlice> backupSlices = List.of();

    private DatabaseFile(Path path, FileChannel channel, Cache cache) {
        this.path = path;
        this.channel = channel;
        this.cache = cache == null ? Cache.of(Cache.DEFAULT_BYTES) : cache;
    }

    static long capacityFor(long logical, int pageSize) {
        long needed = Math.max(logical, (long) pageSize);
        long slack = Math.max(pageSize, needed / 8);
        return Format.alignPage(needed + slack, pageSize);
    }

    static DatabaseFile open(Path file) {
        return open(file, null);
    }

    static DatabaseFile open(Path file, Cache cache) {
        return open(file, Format.DEFAULT_PAGE_SIZE, true, cache, false);
    }

    /** Opens without writing a checkpoint. Used while a log is applied. */
    static DatabaseFile openApplying(Path file, Cache cache) {
        return open(file, Format.DEFAULT_PAGE_SIZE, true, cache, true);
    }

    static DatabaseFile create(Path file, int pageSize) {
        return open(file, Format.checkPageSize(pageSize), false, null, false);
    }

    private static DatabaseFile open(Path file, int pageSize, boolean existing, Cache cache, boolean applying) {
        if (file == null) {
            throw new NexException("database file is required");
        }
        Path path = file.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            throw new NexException("the database is a .db file, not a directory");
        }
        requireDatabase(path);
        Path logPath = TransactionLog.beside(path);
        try {
            if (existing) {
                if (!Files.isRegularFile(path)) {
                    throw new NexException("database file does not exist");
                }
                if (Files.size(path) == 0) {
                    throw new NexException("database file is empty");
                }
            } else if (Files.exists(path) && Files.size(path) > 0) {
                throw new NexException("database file already exists");
            } else if (Files.exists(logPath) && Files.size(logPath) > 0) {
                throw new NexException("transaction log already exists");
            }
            Path parent = path.getParent();
            if (!existing && parent != null) {
                Files.createDirectories(parent);
            }
            boolean created = !existing && !Files.exists(path);
            FileChannel channel = existing
                    ? Format.openHeld(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
                    : Format.openHeld(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            DatabaseFile database = new DatabaseFile(path, channel, cache);
            database.applying = applying;
            try {
                database.lock();
                if (created || channel.size() == 0) {
                    database.pageSize = pageSize;
                    database.cache.accept(pageSize);
                    database.createEmpty();
                } else {
                    database.recover();
                }
                return database;
            } catch (RuntimeException exception) {
                database.close();
                if (created || !existing) {
                    try {
                        Files.deleteIfExists(path);
                        Files.deleteIfExists(logPath);
                    } catch (IOException ignored) {
                        // the empty file is left for the next attempt
                    }
                }
                throw exception;
            }
        } catch (NexException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new NexException("could not open the database: " + exception.getMessage(), exception);
        }
    }

    Path path() {
        return path;
    }

    long fileSize() {
        return fileSize;
    }

    long catalogOffset() {
        return catalogOffset;
    }

    int catalogLength() {
        return catalogLength;
    }

    int pageSize() {
        return pageSize;
    }

    long catalogSpan() {
        return catalogSpan;
    }

    List<StoredTable> tables() {
        return tables;
    }

    Allocator draft() {
        return new Allocator(freeExtents, fileSize, pageSize);
    }

    List<Extent> freeExtents() {
        return List.copyOf(freeExtents);
    }

    /**
     * Copies a region into an earlier gap, without overlapping the source.
     * The catalog points at the destination before the old space is cleared.
     */
    void relocate(long from, long to, long length, List<StoredTable> nextTables) {
        if (length <= 0 || to < pageSize || from < pageSize || to >= from || to + length > from) {
            throw new NexException("internal failure while compacting the database");
        }
        if (overlaps(to, length, catalogOffset, catalogSpan) || overlaps(from, length, catalogOffset, catalogSpan)) {
            throw new NexException("internal failure while compacting the database");
        }
        beginCommit();
        try {
            copy(from, to, length);
            Allocator draft = draft();
            draft.take(to, length);
            draft.free(from, length);
            retire(from, length);
            draft.free(catalogOffset, catalogSpan());
            publish(nextTables, draft);
            scrubPending();
            finishCommit();
        } catch (RuntimeException exception) {
            abortCommit();
            throw exception;
        }
    }

    void copy(long from, long to, long length) {
        if (length < 0 || from < 0 || to < 0) {
            throw new NexException("invalid copy in the database file");
        }
        if (length == 0) {
            return;
        }
        if (from < to + length && to < from + length) {
            throw new NexException("overlapping copy in the database file");
        }
        long remaining = length;
        long readAt = from;
        long writeAt = to;
        while (remaining > 0) {
            int chunk = (int) Math.min(1024 * 1024L, remaining);
            byte[] data = readBytes(readAt, chunk);
            writeBytes(writeAt, data);
            readAt += chunk;
            writeAt += chunk;
            remaining -= chunk;
        }
    }

    void retire(long offset, long length) {
        if (length > 0) {
            retire.add(new Extent(offset, length));
        }
    }

    void zero(long offset, long length) {
        if (length <= 0) {
            return;
        }
        if (commit != null) {
            commit.zero(offset, length);
            return;
        }
        diskZero(offset, length);
    }

    void writeBytes(long offset, byte[] data) {
        if (data.length == 0) {
            return;
        }
        if (commit != null) {
            if (deferDbSync) {
                commit.writeBorrowed(offset, data);
            } else {
                commit.write(offset, data);
            }
            return;
        }
        diskWrite(offset, data);
    }

    byte[] readBytes(long offset, int length) {
        if (commit != null) {
            return commit.read(offset, length, this);
        }
        return readPhysical(offset, length);
    }

    private void diskWrite(long offset, byte[] data) {
        if (deferDbSync) {
            capturing.add(new DeferredIo(DeferredIo.WRITE, offset, data.length, data));
            if (pageSize > 0) {
                cache.forget(path, pageSize, offset, data.length);
            }
            return;
        }
        Format.writeFully(channel, offset, data);
        if (pageSize > 0) {
            cache.forget(path, pageSize, offset, data.length);
        }
    }

    private void diskZero(long offset, long length) {
        if (deferDbSync) {
            capturing.add(new DeferredIo(DeferredIo.ZERO, offset, length, null));
            if (pageSize > 0) {
                cache.forget(path, pageSize, offset, length);
            }
            return;
        }
        Format.zero(channel, offset, length);
        if (pageSize > 0) {
            cache.forget(path, pageSize, offset, length);
        }
    }

    private byte[] readPhysical(long offset, int length) {
        List<DeferredIo> pending = deferDbSync ? capturing : deferredView;
        if (pending != null && !pending.isEmpty()) {
            return readDeferred(pending, offset, length);
        }
        if (pageSize > 0) {
            return cache.read(path, channel, pageSize, offset, length);
        }
        return Format.readFully(channel, offset, length);
    }

    private byte[] readDeferred(List<DeferredIo> pending, long offset, int length) {
        byte[] raw;
        try {
            raw = Format.readFully(channel, offset, length);
        } catch (NexException exception) {
            if (exception.getMessage() == null || !exception.getMessage().contains("truncated")) {
                throw exception;
            }
            raw = new byte[length];
        }
        for (DeferredIo op : pending) {
            if (op.kind == DeferredIo.TRUNCATE) {
                continue;
            }
            long start = Math.max(offset, op.offset);
            long end = Math.min(offset + length, op.offset + op.length);
            if (start >= end) {
                continue;
            }
            int at = (int) (start - offset);
            int count = (int) (end - start);
            if (op.kind == DeferredIo.ZERO) {
                Arrays.fill(raw, at, at + count, (byte) 0);
            } else {
                System.arraycopy(op.data, (int) (start - op.offset), raw, at, count);
            }
        }
        return raw;
    }

    long cacheMaximum() {
        return cache.maximum();
    }

    long cacheResident() {
        return cache.resident();
    }

    /** Appends a checkpoint record and advances the header. The active undo, if any, is stored in the record. */
    void writeCheckpoint() {
        if (crashed || log == null) {
            return;
        }
        checkpoint(true);
    }

    void ensureScrubbed() {
        if (scrubFlag() == Format.SCRUB_CLEAN && wipeAfter.isEmpty()) {
            return;
        }
        wipe(freeExtents);
        wipeAfter = List.of();
        markScrubClean();
    }

    void armScrub() {
        byte[] header = readBytes(0, Format.DB_HEADER_SIZE);
        if (header[Format.SCRUB_OFFSET] == Format.SCRUB_DIRTY) {
            return;
        }
        header[Format.SCRUB_OFFSET] = Format.SCRUB_DIRTY;
        writeBytes(0, header);
        force();
    }

    void scrubPending() {
        List<Extent> retired = wipeAfter;
        try {
            wipe(retired);
            wipeAfter = List.of();
            markScrubClean();
        } catch (RuntimeException first) {
            try {
                wipe(freeExtents);
                wipeAfter = List.of();
                markScrubClean();
            } catch (RuntimeException ignored) {
                throw first;
            }
        }
    }

    void publish(List<StoredTable> nextTables, Allocator allocator) {
        List<Extent> retireNow = new ArrayList<>(retire);
        retire.clear();
        if (catalogSpan > 0) {
            retireNow.add(new Extent(catalogOffset, catalogSpan));
        }
        long committed = fileSize;
        byte[] probe = CatalogCodec.encode(0, nextTables, allocator.free());
        long span = pagedLength(probe.length);
        long at = allocator.allocate(span);
        long publishedSize = allocator.fileSize();
        byte[] catalog = CatalogCodec.encode(publishedSize, nextTables, allocator.free());
        if (pagedLength(catalog.length) > span) {
            throw new NexException("internal failure while writing the catalog");
        }
        byte[] stored = new byte[(int) span];
        System.arraycopy(catalog, 0, stored, 0, catalog.length);
        boolean flipped = false;
        boolean installed = false;
        int target = activeSlot == 0 ? 1 : 0;
        long nextGeneration = generation + 1;
        try {
            writeBytes(at, stored);
            force();
            byte[] header = readBytes(0, Format.DB_HEADER_SIZE);
            ByteBuffer.wrap(header).order(Format.ORDER).putInt(Format.CATALOG_SPAN_OFFSET, (int) span);
            header[Format.SCRUB_OFFSET] = Format.SCRUB_DIRTY;
            FileHeader.writeSlot(header, target, nextGeneration, at, catalog.length);
            writeBytes(0, header);
            flipped = true;
            force();
            install(publishedSize, allocator.free(), nextTables, at, catalog.length, span, nextGeneration, target);
            installed = true;
            wipeAfter = retireNow;
            truncateChannel(publishedSize);
        } catch (RuntimeException exception) {
            if (flipped) {
                if (!installed) {
                    install(publishedSize, allocator.free(), nextTables, at, catalog.length, span, nextGeneration, target);
                }
                wipeAfter = retireNow;
                return;
            }
            truncateChannel(committed);
            throw exception;
        }
    }

    void recoverPending() {
        boolean any = false;
        for (StoredTable table : tables) {
            if (table.pending != null) {
                applyFlip(table);
                any = true;
            }
        }
        if (any) {
            force();
        }
    }

    void truncate(long size) {
        truncateChannel(size);
    }

    void force() {
        if (commit != null || crashed || deferDbSync) {
            return;
        }
        forceChannel();
    }

    /** Page writes of an open transaction stay in memory until {@link #flushDeferredIo()}. */
    void beginDeferredSync() {
        deferDbSync = true;
        deferredCheckpointLsn = -1;
        capturing = new ArrayList<>();
        deferredView = List.of();
    }

    void endDeferredSync() {
        deferDbSync = false;
        if (capturing != null && !capturing.isEmpty()) {
            deferredView = List.copyOf(capturing);
        }
        capturing = null;
    }

    boolean hasDeferredIo() {
        return !deferredView.isEmpty();
    }

    /** Keeps SQL appended after {@code mark} replayable. The pages belong to the earlier snapshot. */
    void limitDeferredCheckpoint(long mark) {
        if (mark >= Format.LOG_HEADER_SIZE && deferredCheckpointLsn > mark) {
            deferredCheckpointLsn = mark;
        }
    }

    /** Writes the pages captured by the last checkpoint. Does not sync and does not move the header. */
    void flushDeferredIo() {
        List<DeferredIo> pending = deferredView;
        if (pending.isEmpty()) {
            return;
        }
        for (DeferredIo op : pending) {
            if (op.kind == DeferredIo.WRITE) {
                Format.writeFully(channel, op.offset, op.data);
            } else if (op.kind == DeferredIo.ZERO) {
                Format.zero(channel, op.offset, op.length);
            } else {
                resizeNow(op.length);
            }
        }
        if (pageSize > 0) {
            cache.release(path);
        }
        deferredView = List.of();
    }

    long deferredCheckpointLsn() {
        return deferredCheckpointLsn;
    }

    void dropDeferredCheckpoint() {
        deferredCheckpointLsn = -1;
    }

    /** Syncs the log after a deferred checkpoint, before the pages reach disk. */
    void forceLog() {
        if (log != null && !crashed) {
            log.force();
        }
        if (!undoForceDeferred) {
            return;
        }
        undoForceDeferred = false;
        if (TransactionLog.crashAfterUndo) {
            crashed = true;
            throw new NexException("simulated crash after the undo record");
        }
    }

    void forceDurable() {
        if (crashed) {
            return;
        }
        forceChannel();
    }

    /**
     * Publishes a checkpoint captured before the file sync.
     * A newer checkpoint wins. Returns whether this call moved the header.
     */
    boolean publishDeferredCheckpoint() {
        synchronized (durableLock) {
            long lsn = deferredCheckpointLsn;
            deferredCheckpointLsn = -1;
            if (lsn <= checkpointLsn || crashed || log == null || lsn > log.size()) {
                return false;
            }
            ByteBuffer buffer = ByteBuffer.allocate(8).order(Format.ORDER);
            buffer.putLong(lsn);
            diskWrite(Format.CHECKPOINT_LSN_OFFSET, buffer.array());
            checkpointLsn = lsn;
            diskWrite(Format.CHECKPOINT_STABLE_OFFSET, new byte[]{0});
            stable = false;
            return true;
        }
    }

    @Override
    public void close() {
        try {
            if (!applying) {
                saveCheckpoint(sessionBoundary);
            }
        } catch (RuntimeException ignored) {
            // the next open recovers from the log
        }
        cache.release(path);
        if (log != null) {
            log.close();
            log = null;
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

    private void createEmpty() {
        byte[] draft = CatalogCodec.encode(0, List.of(), List.of());
        long span = pagedLength(draft.length);
        long size = pageSize + span;
        byte[] catalog = CatalogCodec.encode(size, List.of(), List.of());
        if (catalog.length != draft.length) {
            throw new NexException("internal failure while creating the database");
        }
        byte[] header = new byte[pageSize];
        System.arraycopy(Format.DB_MAGIC, 0, header, 0, Format.DB_MAGIC.length);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        logId = TransactionLog.newId();
        buffer.putShort(Format.VERSION_OFFSET, (short) Format.DB_VERSION);
        buffer.putInt(Format.PAGE_SIZE_OFFSET, pageSize);
        buffer.putInt(Format.CATALOG_SPAN_OFFSET, (int) span);
        buffer.putLong(Format.LOG_ID_OFFSET, logId);
        buffer.putLong(Format.CHECKPOINT_LSN_OFFSET, Format.LOG_HEADER_SIZE);
        buffer.putLong(Format.LOG_EPOCH_OFFSET, 1L);
        buffer.putLong(Format.APPLIED_OFFSET, 0L);
        header[Format.SCRUB_OFFSET] = Format.SCRUB_CLEAN;
        header[Format.CHECKPOINT_STABLE_OFFSET] = 0;
        FileHeader.writeSlot(header, 0, 1L, pageSize, catalog.length);
        writeBytes(0, header);
        writeBytes(pageSize, paged(catalog));
        forceChannel();
        install(size, List.of(), List.of(), pageSize, catalog.length, span, 1L, 0);
        truncatePhysical(size);
        epoch = 1L;
        log = TransactionLog.create(path, logId, pageSize, epoch);
        checkpointLsn = Format.LOG_HEADER_SIZE;
        ready = true;
        saveCheckpoint();
    }

    private byte[] paged(byte[] catalog) {
        long span = pagedLength(catalog.length);
        if (span == catalog.length) {
            return catalog;
        }
        byte[] stored = new byte[(int) span];
        System.arraycopy(catalog, 0, stored, 0, catalog.length);
        return stored;
    }

    private long pagedLength(int length) {
        long span = Format.alignPage(length, pageSize);
        if (span > Integer.MAX_VALUE) {
            throw new NexException("catalog exceeds the maximum supported size");
        }
        return span;
    }

    private void recover() {
        byte[] header = readBytes(0, Format.DB_HEADER_SIZE);
        if (!Arrays.equals(header, 0, Format.DB_MAGIC.length, Format.DB_MAGIC, 0, Format.DB_MAGIC.length)) {
            throw new NexException("file is not a Nexora database");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        int version = buffer.getShort(Format.VERSION_OFFSET) & 0xFFFF;
        if (version != Format.DB_VERSION) {
            throw new NexException("unsupported database version: " + version);
        }
        pageSize = Format.checkPageSize(buffer.getInt(Format.PAGE_SIZE_OFFSET));
        cache.accept(pageSize);
        logId = buffer.getLong(Format.LOG_ID_OFFSET);
        if (logId == 0L) {
            throw new NexException("database has no transaction log");
        }
        checkpointLsn = buffer.getLong(Format.CHECKPOINT_LSN_OFFSET);
        epoch = buffer.getLong(Format.LOG_EPOCH_OFFSET);
        appliedOffset = buffer.getLong(Format.APPLIED_OFFSET);
        if (TransactionLog.missing(path, applying)) {
            long next = epoch + 1;
            writeEpoch(next);
            writeAppliedOffset(0L);
            writeCheckpointLsn(Format.LOG_HEADER_SIZE);
            forceChannel();
            log = TransactionLog.create(path, logId, pageSize, next);
        } else {
            log = TransactionLog.open(path, logId, pageSize, applying);
        }
        long logEpoch = log.epoch();
        if (!applying) {
            if (logEpoch != epoch || checkpointLsn != log.size() || !log.cleanCheckpoint(checkpointLsn)) {
                throw new NexException("checkpoint mismatch between database and transaction log (generation "
                        + epoch + "/" + logEpoch + ", database checkpoint " + checkpointLsn + ", log end " + log.size()
                        + "); apply the log explicitly using a tool with -a before opening the database");
            }
        } else {
            if (logEpoch == epoch + 1) {
                checkpointLsn = Format.LOG_HEADER_SIZE;
            } else if (logEpoch != epoch) {
                throw new NexException(logEpoch < epoch
                        ? "log is from a previous generation of the database"
                        : "a log is missing between the database and this file");
            }
            if (checkpointLsn < Format.LOG_HEADER_SIZE || checkpointLsn > log.size()) {
                throw new NexException("transaction log does not cover the checkpoint");
            }
            log.replay(checkpointLsn, this::applyRedo);
            log.truncate(log.consistentEnd());
            if (checkpointLsn > log.size()) {
                throw new NexException("transaction log does not cover the checkpoint");
            }
            pendingUndos = log.transactionsToUndo(checkpointLsn);
            undoPending = !pendingUndos.isEmpty();
            sqlReplay = log.sqlToReplay(checkpointLsn);
            if (logEpoch != epoch) {
                writeEpoch(logEpoch);
            }
        }
        header = readBytes(0, Format.DB_HEADER_SIZE);
        buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        int reserved = buffer.getInt(Format.CATALOG_SPAN_OFFSET);
        FileHeader slots = FileHeader.parseSlots(header);
        FileHeader.Slot active = slots.active();
        if (reserved <= 0 || reserved % pageSize != 0 || reserved < active.metaLength()) {
            throw new NexException("database catalog is corrupt");
        }
        byte[] encoded = readBytes(active.metaOffset(), active.metaLength());
        CatalogImage image = CatalogCodec.decode(encoded, pageSize);
        if (!applying && image.tables.stream().anyMatch(table -> table.pending != null)) {
            throw new NexException("checkpoint has pending table changes; apply the log explicitly using -a");
        }
        if (active.metaOffset() + reserved > image.fileSize) {
            throw new NexException("database catalog is corrupt");
        }
        install(image.fileSize, image.free, image.tables, active.metaOffset(), active.metaLength(),
                reserved, active.generation(), slots.activeIndex());
        truncateChannel(fileSize);
        ensureScrubbed();
        boolean pending = false;
        for (StoredTable table : tables) {
            if (table.pending != null) {
                pending = true;
                break;
            }
        }
        if (!pending) {
            ready = true;
            if (!applying && !undoPending) {
                saveCheckpoint();
            }
            return;
        }
        beginCommit();
        try {
            for (StoredTable table : tables) {
                if (table.pending != null) {
                    applyFlip(table);
                }
            }
            List<StoredTable> clean = new ArrayList<>();
            for (StoredTable table : tables) {
                clean.add(new StoredTable(table.name, table.base, table.capacity, null));
            }
            Allocator allocator = draft();
            allocator.free(catalogOffset, catalogSpan());
            publish(clean, allocator);
            scrubPending();
            finishCommit();
            ready = true;
            if (!applying && !undoPending) {
                saveCheckpoint();
            }
        } catch (RuntimeException exception) {
            abortCommit();
            throw exception;
        }
    }

    private void applyFlip(StoredTable table) {
        PendingFlip flip = table.pending;
        byte[] header = readBytes(table.base, Format.HEADER_SIZE);
        FileHeader parsed = FileHeader.parse(header);
        if (parsed.active().generation() >= flip.generation) {
            return;
        }
        header[Format.SCRUB_OFFSET] = Format.SCRUB_DIRTY;
        FileHeader.writeSlot(header, flip.slot, flip.generation, flip.metaOffset, flip.metaLength);
        writeBytes(table.base, header);
    }

    private void install(long size, List<Extent> free, List<StoredTable> nextTables, long catalogAt, int catalogBytes,
                         long span, long nextGeneration, int slot) {
        this.fileSize = size;
        this.freeExtents = new ArrayList<>(free);
        this.tables = List.copyOf(nextTables);
        this.catalogOffset = catalogAt;
        this.catalogLength = catalogBytes;
        this.catalogSpan = span;
        this.generation = nextGeneration;
        this.activeSlot = slot;
    }

    private byte scrubFlag() {
        return readBytes(0, Format.DB_HEADER_SIZE)[Format.SCRUB_OFFSET];
    }

    private void markScrubClean() {
        byte[] header = readBytes(0, Format.DB_HEADER_SIZE);
        if (header[Format.SCRUB_OFFSET] == Format.SCRUB_CLEAN) {
            return;
        }
        header[Format.SCRUB_OFFSET] = Format.SCRUB_CLEAN;
        writeBytes(0, header);
        force();
    }

    private void wipe(List<Extent> ranges) {
        List<Extent> victims = Extent.intersect(ranges, freeExtents);
        for (Extent extent : victims) {
            if (extent.offset < pageSize || extent.offset + extent.length > fileSize) {
                throw new NexException("free space is outside the file");
            }
            if (overlaps(extent.offset, extent.length, catalogOffset, catalogSpan)) {
                throw new NexException("free space overlaps the catalog");
            }
            for (StoredTable table : tables) {
                if (overlaps(extent.offset, extent.length, table.base, table.capacity)) {
                    throw new NexException("free space overlaps table " + table.name);
                }
            }
        }
        if (victims.isEmpty()) {
            return;
        }
        for (Extent extent : victims) {
            zero(extent.offset, extent.length);
        }
        force();
    }

    private static boolean overlaps(long left, long leftLength, long right, long rightLength) {
        return leftLength > 0 && rightLength > 0 && left < right + rightLength && right < left + leftLength;
    }

    private void truncateChannel(long size) {
        if (commit != null) {
            commit.setFileSize(size);
            return;
        }
        truncatePhysical(size);
    }

    private void truncatePhysical(long size) {
        if (deferDbSync) {
            capturing.add(new DeferredIo(DeferredIo.TRUNCATE, 0, size, null));
            if (pageSize > 0) {
                cache.release(path);
            }
            return;
        }
        resizeNow(size);
    }

    private void resizeNow(long size) {
        try {
            long current = channel.size();
            if (current > size) {
                channel.truncate(size);
            } else if (current < size) {
                ByteBuffer zero = ByteBuffer.allocate(1);
                if (channel.write(zero, size - 1) != 1) {
                    throw new NexException("failed to resize the database file");
                }
            }
            cache.release(path);
        } catch (NexException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new NexException("failed to resize the database file: " + exception.getMessage(), exception);
        }
    }

    private static final class DeferredIo {
        static final int WRITE = 1;
        static final int ZERO = 2;
        static final int TRUNCATE = 3;

        final int kind;
        final long offset;
        final long length;
        final byte[] data;

        DeferredIo(int kind, long offset, long length, byte[] data) {
            this.kind = kind;
            this.offset = offset;
            this.length = length;
            this.data = data;
        }
    }

    void beginCommit() {
        if (leaveForRecovery()) {
            throw new NexException("the database must be reopened to finish recovery");
        }
        if (commit != null) {
            throw new NexException("internal failure: commit already started");
        }
        commit = new CommitBuffer(fileSize);
    }

    void finishCommit() {
        finishCommit(List.of(), null, null);
    }

    void finishCommit(List<TransactionLog.Applied> statements) {
        finishCommit(statements, null, null);
    }

    /**
     * {@code commitId} and {@code rollbackId} are written before the page redo and forced with it.
     * Neither one is a checkpoint, and neither one is implied by writing pages.
     */
    void finishCommit(List<TransactionLog.Applied> statements, Long commitId, Long rollbackId) {
        if (commit == null) {
            return;
        }
        CommitBuffer buffer = commit;
        commit = null;
        if (buffer.unchanged()) {
            return;
        }
        if (applying) {
            buffer.apply(this);
            truncatePhysical(buffer.fileSize());
            fileSize = buffer.fileSize();
            forceChannel();
            return;
        }
        if (commitId != null) {
            log.appendCommit(commitId);
        }
        if (rollbackId != null) {
            log.appendRollback(rollbackId);
        }
        if (statements != null && !statements.isEmpty()) {
            log.appendTransaction(statements);
            statements.clear();
        }
        log.appendRedo(buffer.spans(), buffer.fileSize());
        recoveryPending = true;
        log.force();
        if (TransactionLog.crashAfterRedo) {
            crashed = true;
            throw new NexException("simulated crash before the checkpoint");
        }
        buffer.apply(this);
        truncatePhysical(buffer.fileSize());
        fileSize = buffer.fileSize();
        appliedToDisk = true;
        checkpoint();
    }

    /**
     * Writes the commit buffer to the database file and forces it.
     * No transaction record and no checkpoint LSN update: the caller logs the undo first.
     */
    void finishPages() {
        if (commit == null) {
            return;
        }
        CommitBuffer buffer = commit;
        commit = null;
        if (!buffer.unchanged()) {
            buffer.apply(this);
            truncatePhysical(buffer.fileSize());
            fileSize = buffer.fileSize();
            if (!deferDbSync) {
                forceChannel();
            }
        }
        if (TransactionLog.crashAfterCheckpointPages) {
            crashed = true;
            throw new NexException("simulated crash during the checkpoint");
        }
    }

    List<TransactionLog.Batch> takeSqlReplay() {
        List<TransactionLog.Batch> replay = sqlReplay;
        sqlReplay = List.of();
        return replay == null ? List.of() : replay;
    }

    /**
     * Writes the statement text of an open transaction. The file sync stays on the
     * checkpoint thread and on commit, so an insert does not wait for the disk.
     */
    void logStatements(long txnId, List<TransactionLog.Applied> statements) {
        if (log == null || statements == null || statements.isEmpty()) {
            return;
        }
        log.appendTxnSql(txnId, statements);
    }

    /** The commit record. Pages stay in memory until a later flush. */
    void logCommit(long txnId) {
        if (log == null) {
            throw new NexException("transaction log is required");
        }
        log.appendCommit(txnId);
        log.force();
    }

    /** Drops SQL that was written while the transaction was open. */
    void logRollback(long txnId) {
        if (log == null) {
            throw new NexException("transaction log is required");
        }
        log.appendRollback(txnId);
        log.force();
    }

    /** Remembers undo bytes already compressed off the insert thread. */
    void armPackedCheckpoint(List<byte[]> undos, byte[] checkpoint) {
        packedUndos = undos;
        packedCheckpoint = checkpoint;
    }

    void disarmPackedCheckpoint() {
        packedUndos = null;
        packedCheckpoint = null;
    }

    byte[] packUndo(TransactionLog.TxnUndo undo) {
        return log.packedUndo(undo);
    }

    byte[] packActive(List<TransactionLog.TxnUndo> active) {
        return log.packedActive(active);
    }

    /** Forces the undo images. Pages that depend on them must be written only after this returns. */
    void logUndo(List<TransactionLog.TxnUndo> undos) {
        if (log == null) {
            throw new NexException("transaction log is required");
        }
        if (packedUndos != null) {
            for (byte[] packed : packedUndos) {
                log.appendPacked(Format.RECORD_UNDO, packed);
            }
            packedUndos = null;
        } else if (undos != null) {
            for (TransactionLog.TxnUndo undo : undos) {
                log.appendUndo(undo);
            }
        }
        if (deferDbSync) {
            undoForceDeferred = true;
            return;
        }
        log.force();
        if (TransactionLog.crashAfterUndo) {
            crashed = true;
            throw new NexException("simulated crash after the undo record");
        }
    }

    void activeUndo(List<TransactionLog.TxnUndo> undos) {
        activeUndo = undos == null || undos.isEmpty() ? List.of() : List.copyOf(undos);
    }

    boolean undoPending() {
        return undoPending;
    }

    List<TransactionLog.TxnUndo> pendingUndos() {
        return pendingUndos;
    }

    void clearUndoPending() {
        undoPending = false;
        pendingUndos = List.of();
    }

    /** Closes the files without a checkpoint. Used when a crash is simulated. */
    void abandon() {
        crashed = true;
        close();
    }

    void abortCommit() {
        if (recoveryPending) {
            return;
        }
        if (commit != null) {
            fileSize = commit.originalSize();
            commit = null;
        }
    }

    boolean leaveForRecovery() {
        return crashed || recoveryPending;
    }

    /**
     * Writes the checkpoint once opening has finished. A rejected log is left unchanged.
     */
    void saveCheckpoint() {
        saveCheckpoint(false);
    }

    /** Start of a normal session: the checkpoint is stored in the database and in the log. */
    void beginSession() {
        checkpoint(true);
        sessionBoundary = true;
    }

    /** Only a successfully accepted explicit apply may synchronize recovery results. */
    void finishApply() {
        boolean synchronizedCheckpoint = log.cleanCheckpoint(checkpointLsn);
        applying = false;
        if (!synchronizedCheckpoint) {
            checkpoint(true);
        }
    }

    long checkpointLsn() {
        return checkpointLsn;
    }

    long logLength() {
        return log.logicalLength();
    }

    private void saveCheckpoint(boolean boundary) {
        if (applying || !ready || log == null || crashed || undoPending || (recoveryPending && !appliedToDisk)) {
            return;
        }
        checkpoint(boundary);
    }

    private void checkpoint() {
        checkpoint(false);
    }

    /**
     * Syncs the pages and points the header at the end of the log.
     * A boundary, written on start and on stop, always appends a checkpoint record.
     */
    private void checkpoint(boolean boundary) {
        if (applying) {
            return;
        }
        if (!deferDbSync) {
            forceChannel();
        }
        if (packedCheckpoint != null || boundary || log.size() > checkpointLsn || !activeUndo.isEmpty()) {
            if (packedCheckpoint != null) {
                log.appendPacked(Format.RECORD_CHECKPOINT, packedCheckpoint);
                packedCheckpoint = null;
            } else {
                log.appendCheckpoint(activeUndo);
            }
            if (!deferDbSync) {
                log.force();
            }
        }
        if (deferDbSync) {
            // Header moves only after the database file is synced, and only to this
            // offset. SQL appended during the sync stays after it and can be replayed.
            deferredCheckpointLsn = log.logicalLength();
            recoveryPending = false;
            appliedToDisk = false;
            return;
        }
        writeCheckpointLsn(log.size());
        writeStable(false);
        forceChannel();
        recoveryPending = false;
        appliedToDisk = false;
    }

    /**
     * Full backup: copies the pair already synced and makes the live log
     * record only what changes afterward.
     */
    void backupTo(Path directory) {
        Path dir = backupFolder(directory);
        checkpoint();
        Path dbCopy = dir.resolve(path.getFileName());
        Path logCopy = dir.resolve(log.path.getFileName());
        if (dbCopy.equals(path) || logCopy.equals(log.path)) {
            throw new NexException("the backup cannot be placed in the database directory");
        }
        long bytes;
        try {
            bytes = channel.size();
        } catch (IOException exception) {
            throw new NexException("failed to read the database file: " + exception.getMessage(), exception);
        }
        try {
            copyRange(channel, bytes, dbCopy);
            log.transferTo(logCopy);
        } catch (RuntimeException exception) {
            deleteQuiet(dbCopy);
            deleteQuiet(logCopy);
            throw exception;
        }
        rollLog();
    }

    /**
     * Checkpoint and remember the files a remote backup will read.
     * The live log moves to the next generation only in {@link #finishBackup},
     * after those bytes have been copied.
     */
    List<BackupPiece> beginBackup(boolean incremental) {
        if (backupActive) {
            throw new NexException("a backup is already running");
        }
        if (!ready || log == null || crashed || recoveryPending) {
            throw new NexException("the database is not ready for backup");
        }
        checkpoint();
        long dbBytes;
        try {
            dbBytes = channel.size();
        } catch (IOException exception) {
            throw new NexException("failed to read the database file: " + exception.getMessage(), exception);
        }
        String dbName = fileName(path);
        String logName = fileName(log.path);
        backupFull = !incremental;
        backupSlices = incremental
                ? List.of(new BackupSlice(logName, log.size(), true))
                : List.of(new BackupSlice(dbName, dbBytes, false), new BackupSlice(logName, log.size(), true));
        backupActive = true;
        List<BackupPiece> pieces = new ArrayList<>();
        for (BackupSlice slice : backupSlices) {
            pieces.add(new BackupPiece(slice.name, slice.size));
        }
        return List.copyOf(pieces);
    }

    /** Reads a slice captured by {@link #beginBackup}. The file is not copied again. */
    byte[] readBackup(int index, long offset, int length) {
        if (!backupActive) {
            throw new NexException("there is no backup in progress");
        }
        if (index < 0 || index >= backupSlices.size()) {
            throw new NexException("unknown backup file");
        }
        if (offset < 0 || length < 0 || length > BackupPiece.CHUNK) {
            throw new NexException("invalid backup read");
        }
        BackupSlice slice = backupSlices.get(index);
        if (offset > slice.size) {
            throw new NexException("backup read is past the end of the file");
        }
        int want = (int) Math.min(length, slice.size - offset);
        if (want == 0) {
            return new byte[0];
        }
        if (slice.log) {
            return log.readAt(offset, want);
        }
        return Format.readFully(channel, offset, want);
    }

    /** Full backup advances the live log. An incremental backup leaves it in place. */
    void finishBackup() {
        if (!backupActive) {
            throw new NexException("there is no backup in progress");
        }
        if (backupFull) {
            rollLog();
        }
        endBackup();
    }

    void abortBackup() {
        endBackup();
    }

    private void rollLog() {
        long next = epoch + 1;
        log.restart(next);
        writeEpoch(next);
        writeAppliedOffset(0L);
        writeCheckpointLsn(Format.LOG_HEADER_SIZE);
        forceChannel();
    }

    private void endBackup() {
        backupActive = false;
        backupFull = false;
        backupSlices = List.of();
    }

    private static String fileName(Path file) {
        Path name = file.getFileName();
        if (name == null || name.toString().isBlank()) {
            throw new NexException("invalid backup file name");
        }
        return name.toString();
    }

    private static final class BackupSlice {
        private final String name;
        private final long size;
        private final boolean log;

        private BackupSlice(String name, long size, boolean log) {
            this.name = name;
            this.size = size;
            this.log = log;
        }
    }

    /**
     * Modification backup: copies the {@code .log} and leaves the live log in place.
     */
    void backupLogTo(Path directory) {
        Path dir = backupFolder(directory);
        checkpoint();
        Path logCopy = dir.resolve(log.path.getFileName());
        if (logCopy.equals(log.path)) {
            throw new NexException("the backup cannot be placed in the database directory");
        }
        try {
            log.transferTo(logCopy);
        } catch (RuntimeException exception) {
            deleteQuiet(logCopy);
            throw exception;
        }
    }

    private Path backupFolder(Path directory) {
        if (directory == null) {
            throw new NexException("backup directory is required");
        }
        Path dir = directory.toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent != null && dir.equals(parent)) {
            throw new NexException("the backup cannot be placed in the database directory");
        }
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw new NexException("the backup destination is a directory");
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException exception) {
            throw new NexException("could not create the backup directory: " + exception.getMessage(), exception);
        }
        return dir;
    }

    /**
     * Reads the transactions from {@code logFile} that this database does not have yet.
     * The same generation continues from the checkpoint. The next generation brings what changed after the backup.
     */
    ApplyPlan planApply(Path logFile) {
        if (logFile == null) {
            throw new NexException("transaction log is required");
        }
        Path externalPath = logFile.toAbsolutePath().normalize();
        if (externalPath.equals(log.path.toAbsolutePath().normalize())) {
            long mark = log.lastCheckpoint();
            if (canAdopt(log, mark)) {
                adoptCheckpoint(mark);
            }
            return ApplyPlan.none();
        }
        TransactionLog external = TransactionLog.openFile(externalPath, logId, pageSize);
        try {
            long mark = external.lastCheckpoint();
            boolean adopt = canAdopt(external, mark);
            long incoming = external.epoch();
            if (incoming == epoch) {
                long from = appliedOffset > 0 ? appliedOffset : checkpointLsn;
                if (external.size() < from) {
                    throw new NexException("transaction log does not cover the checkpoint");
                }
                if (external.size() <= from) {
                    return ApplyPlan.none();
                }
                return new ApplyPlan(incoming, false, external.readTransactions(from), external.size(), mark, adopt);
            }
            if (incoming == epoch + 1) {
                if (log.size() != checkpointLsn) {
                    throw new NexException("the database still has changes without a checkpoint");
                }
                long from = appliedOffset > Format.LOG_HEADER_SIZE ? appliedOffset : Format.LOG_HEADER_SIZE;
                return new ApplyPlan(incoming, true, external.readTransactions(from), external.size(), mark, adopt);
            }
            if (incoming < epoch) {
                throw new NexException("log is from a previous generation of the database");
            }
            throw new NexException("a log is missing between the database and this file");
        } finally {
            external.close();
        }
    }

    /** The offset belongs to this database's log, so the header can store it. */
    private boolean canAdopt(TransactionLog external, long mark) {
        if (mark < checkpointLsn || mark > log.size()) {
            return false;
        }
        if (external.path.toAbsolutePath().normalize().equals(log.path.toAbsolutePath().normalize())) {
            return true;
        }
        return external.epoch() == epoch && log.matches(external, mark);
    }

    /** Stores the checkpoint taken from a log, without appending a new record. */
    private void adoptCheckpoint(long mark) {
        if (mark < Format.LOG_HEADER_SIZE || mark <= checkpointLsn || mark > log.size()) {
            return;
        }
        writeCheckpointLsn(mark);
        writeStable(false);
        forceChannel();
    }

    /** Records how far the external log has been applied, so a new apply continues from there. */
    void noteApplied(long end) {
        writeAppliedOffset(end);
        if (applying) {
            forceChannel();
            return;
        }
        checkpoint();
    }

    /** The next generation becomes this database's generation. The checkpoint comes from the log. */
    void completeApply(ApplyPlan plan) {
        if (plan.advanceEpoch) {
            log.adoptEpoch(plan.incomingEpoch);
            writeEpoch(plan.incomingEpoch);
        }
        if (plan.end > appliedOffset) {
            writeAppliedOffset(plan.end);
        }
        if (plan.adoptCheckpoint) {
            adoptCheckpoint(plan.checkpointInLog);
        }
        forceChannel();
    }

    private void applyRedo(long size, List<TransactionLog.Span> spans) {
        for (TransactionLog.Span span : spans) {
            if (span.data == null) {
                diskZero(span.offset, span.length);
            } else {
                diskWrite(span.offset, span.data);
            }
        }
        truncatePhysical(size);
        fileSize = size;
    }

    private void writeEpoch(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(Format.ORDER);
        buffer.putLong(value);
        diskWrite(Format.LOG_EPOCH_OFFSET, buffer.array());
        epoch = value;
    }

    private void writeAppliedOffset(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(Format.ORDER);
        buffer.putLong(value);
        diskWrite(Format.APPLIED_OFFSET, buffer.array());
        appliedOffset = value;
    }

    private static void copyRange(FileChannel source, long size, Path target) {
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long pos = 0;
            while (pos < size) {
                int want = (int) Math.min(1024 * 1024, size - pos);
                byte[] data = Format.readFully(source, pos, want);
                Format.writeFully(out, pos, data);
                pos += data.length;
            }
            out.force(true);
        } catch (IOException exception) {
            throw new NexException("failed to copy the database: " + exception.getMessage(), exception);
        }
    }

    private static void deleteQuiet(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the incomplete copy is left for the next attempt
        }
    }

    static final class ApplyPlan {
        final long incomingEpoch;
        final boolean advanceEpoch;
        final List<TransactionLog.Batch> batches;
        final long end;
        final long checkpointInLog;
        final boolean adoptCheckpoint;

        private ApplyPlan(long incomingEpoch, boolean advanceEpoch, List<TransactionLog.Batch> batches, long end,
                          long checkpointInLog, boolean adoptCheckpoint) {
            this.incomingEpoch = incomingEpoch;
            this.advanceEpoch = advanceEpoch;
            this.batches = batches;
            this.end = end;
            this.checkpointInLog = checkpointInLog;
            this.adoptCheckpoint = adoptCheckpoint;
        }

        static ApplyPlan none() {
            return new ApplyPlan(0L, false, List.of(), 0L, 0L, false);
        }

        boolean idle() {
            return batches.isEmpty() && !advanceEpoch;
        }
    }

    private void writeCheckpointLsn(long lsn) {
        synchronized (durableLock) {
            deferredCheckpointLsn = -1;
            ByteBuffer buffer = ByteBuffer.allocate(8).order(Format.ORDER);
            buffer.putLong(lsn);
            diskWrite(Format.CHECKPOINT_LSN_OFFSET, buffer.array());
            checkpointLsn = lsn;
        }
    }

    private void writeStable(boolean value) {
        diskWrite(Format.CHECKPOINT_STABLE_OFFSET, new byte[]{(byte) (value ? Format.CHECKPOINT_STABLE : 0)});
        stable = value;
    }

    private void forceChannel() {
        try {
            channel.force(true);
        } catch (IOException exception) {
            throw new NexException("failed to sync the database file: " + exception.getMessage(), exception);
        }
    }

    private static void requireDatabase(Path file) {
        Path name = file.getFileName();
        if (name == null || !name.toString().toLowerCase(Locale.ROOT).endsWith(".db")) {
            throw new NexException("the database is a .db file");
        }
    }

    private void lock() {
        try {
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                lock = null;
            }
            if (lock == null) {
                throw new NexException("database is already open");
            }
        } catch (NexException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new NexException("could not lock the database: " + exception.getMessage(), exception);
        }
    }

    static final class Allocator {
        private final Space space;
        private final int pageSize;

        Allocator(List<Extent> free, long fileSize, int pageSize) {
            this.space = new Space(free, fileSize);
            this.pageSize = pageSize;
        }

        long allocate(long size) {
            long bytes = Format.alignPage(size, pageSize);
            long offset = space.allocate(bytes);
            if (offset < pageSize || offset % pageSize != 0) {
                throw new NexException("internal failure while reserving space in the database");
            }
            return offset;
        }

        boolean extendTail(long offset, long oldLength, long newLength) {
            if (newLength % pageSize != 0 || offset % pageSize != 0) {
                return false;
            }
            return space.growTail(offset, oldLength, newLength);
        }

        void free(long offset, long length) {
            space.release(offset, length);
        }

        void take(long offset, long length) {
            space.take(offset, length);
        }

        long fileSize() {
            return space.fileSize();
        }

        List<Extent> free() {
            return space.remaining();
        }
    }

    record PendingFlip(long generation, long metaOffset, int metaLength, int slot) {
    }

    record StoredTable(String name, long base, long capacity, PendingFlip pending) {
    }

    private record CatalogImage(long fileSize, List<StoredTable> tables, List<Extent> free) {
    }

    private static final class CatalogCodec {
        private CatalogCodec() {
        }

        static byte[] encode(long fileSize, List<StoredTable> tables, List<Extent> free) {
            ByteWriter writer = new ByteWriter();
            writer.u32(Format.CATALOG_MAGIC);
            writer.u32(Format.DB_VERSION);
            writer.u64(fileSize);
            writer.u32(tables.size());
            for (StoredTable table : tables) {
                writer.utf(table.name);
                writer.u64(table.base);
                writer.u64(table.capacity);
                if (table.pending == null) {
                    writer.u8(0);
                } else {
                    writer.u8(1);
                    writer.u64(table.pending.generation);
                    writer.u64(table.pending.metaOffset);
                    writer.u32(table.pending.metaLength);
                    writer.u8(table.pending.slot);
                }
            }
            writer.u32(free.size());
            for (Extent extent : free) {
                writer.u64(extent.offset);
                writer.u64(extent.length);
            }
            int pad = (8 - ((writer.size() + 2 + 4) % 8)) % 8;
            writer.u16(pad);
            writer.zeros(pad);
            byte[] body = writer.toArray();
            int crc = Format.crc(body, 0, body.length);
            ByteWriter full = new ByteWriter();
            full.raw(body);
            full.u32(crc);
            return full.toArray();
        }

        static CatalogImage decode(byte[] data, int pageSize) {
            if (data.length < 16 || (data.length % 8) != 0) {
                throw new NexException("database catalog is corrupt");
            }
            int stored = ByteBuffer.wrap(data, data.length - 4, 4).order(Format.ORDER).getInt();
            if (stored != Format.crc(data, 0, data.length - 4)) {
                throw new NexException("database catalog failed verification");
            }
            ByteReader reader = new ByteReader(data, data.length - 4);
            if (reader.u32() != Format.CATALOG_MAGIC) {
                throw new NexException("database catalog is corrupt");
            }
            int version = reader.u32();
            if (version != Format.DB_VERSION) {
                throw new NexException("unsupported catalog version: " + version);
            }
            long size = reader.u64();
            int count = reader.u32();
            if (count < 0) {
                throw new NexException("database catalog is corrupt");
            }
            List<StoredTable> tables = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String name = reader.utf();
                long base = reader.u64();
                long capacity = reader.u64();
                PendingFlip pending = null;
                if (reader.u8() == 1) {
                    pending = new PendingFlip(reader.u64(), reader.u64(), reader.u32(), reader.u8());
                }
                if (base < pageSize || base % pageSize != 0 || capacity <= 0 || capacity % pageSize != 0 || base + capacity < base) {
                    throw new NexException("database catalog is corrupt");
                }
                tables.add(new StoredTable(name, base, capacity, pending));
            }
            int freeCount = reader.u32();
            if (freeCount < 0) {
                throw new NexException("database catalog is corrupt");
            }
            List<Extent> free = new ArrayList<>(freeCount);
            for (int i = 0; i < freeCount; i++) {
                long offset = reader.u64();
                long length = reader.u64();
                if (offset < pageSize || offset % pageSize != 0 || length <= 0 || length % pageSize != 0) {
                    throw new NexException("database catalog is corrupt");
                }
                free.add(new Extent(offset, length));
            }
            int pad = reader.u16();
            reader.skip(pad);
            if (reader.remaining() != 0) {
                throw new NexException("database catalog is corrupt");
            }
            if (size < pageSize || size % pageSize != 0) {
                throw new NexException("database catalog is corrupt");
            }
            return new CatalogImage(size, tables, Extent.coalesce(free));
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

        void u16(int value) {
            out.write((value >>> 8) & 0xFF);
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

        void zeros(int count) {
            for (int i = 0; i < count; i++) {
                out.write(0);
            }
        }

        void raw(byte[] data) {
            out.write(data, 0, data.length);
        }

        void utf(String text) {
            byte[] raw = text.getBytes(StandardCharsets.UTF_8);
            if (raw.length > 65535) {
                throw new NexException("name is too long in the catalog");
            }
            u16(raw.length);
            raw(raw);
        }

        byte[] toArray() {
            return out.toByteArray();
        }
    }

    private static final class ByteReader {
        private final byte[] data;
        private final int end;
        private int position;

        ByteReader(byte[] data, int end) {
            this.data = data;
            this.end = end;
        }

        int remaining() {
            return end - position;
        }

        int u8() {
            need(1);
            return data[position++] & 0xFF;
        }

        int u16() {
            need(2);
            int value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
            position += 2;
            return value;
        }

        int u32() {
            need(4);
            int value = ((data[position] & 0xFF) << 24)
                    | ((data[position + 1] & 0xFF) << 16)
                    | ((data[position + 2] & 0xFF) << 8)
                    | (data[position + 3] & 0xFF);
            position += 4;
            return value;
        }

        long u64() {
            need(8);
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (data[position++] & 0xFFL);
            }
            return value;
        }

        String utf() {
            int length = u16();
            need(length);
            String text = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            return text;
        }

        void skip(int count) {
            need(count);
            position += count;
        }

        private void need(int count) {
            if (count < 0 || position + count > end) {
                throw new NexException("database catalog is corrupt");
            }
        }
    }

    private static final class CommitBuffer {
        private final TreeMap<Long, TransactionLog.Span> spans = new TreeMap<>();
        private final long original;
        private long fileSize;

        CommitBuffer(long fileSize) {
            this.original = fileSize;
            this.fileSize = fileSize;
        }

        boolean unchanged() {
            return spans.isEmpty() && fileSize == original;
        }

        long originalSize() {
            return original;
        }

        Collection<TransactionLog.Span> spans() {
            return spans.values();
        }

        long fileSize() {
            return fileSize;
        }

        void write(long offset, byte[] data) {
            put(new TransactionLog.Span(offset, data.length, data.clone()));
        }

        void writeBorrowed(long offset, byte[] data) {
            put(new TransactionLog.Span(offset, data.length, data));
        }

        void zero(long offset, long length) {
            put(new TransactionLog.Span(offset, length, null));
        }

        void setFileSize(long size) {
            if (size < 0) {
                throw new NexException("failed to resize the database file");
            }
            List<Long> remove = new ArrayList<>();
            List<TransactionLog.Span> keep = new ArrayList<>();
            for (TransactionLog.Span span : spans.values()) {
                if (span.offset >= size) {
                    remove.add(span.offset);
                } else if (span.end() > size) {
                    remove.add(span.offset);
                    keep.add(span.slice(span.offset, size));
                }
            }
            for (Long key : remove) {
                spans.remove(key);
            }
            for (TransactionLog.Span span : keep) {
                spans.put(span.offset, span);
            }
            fileSize = size;
        }

        void apply(DatabaseFile file) {
            long runAt = -1;
            byte[] run = null;
            int runLength = 0;
            for (TransactionLog.Span span : spans.values()) {
                if (span.data != null && run != null && runAt + runLength == span.offset
                        && (long) runLength + span.data.length <= Integer.MAX_VALUE) {
                    int needed = runLength + span.data.length;
                    if (needed > run.length) {
                        run = Arrays.copyOf(run, Math.max(needed, run.length * 2));
                    }
                    System.arraycopy(span.data, 0, run, runLength, span.data.length);
                    runLength = needed;
                    continue;
                }
                if (run != null) {
                    file.diskWrite(runAt, runLength == run.length ? run : Arrays.copyOf(run, runLength));
                    run = null;
                }
                if (span.data == null) {
                    file.diskZero(span.offset, span.length);
                    continue;
                }
                runAt = span.offset;
                run = span.data;
                runLength = span.data.length;
            }
            if (run != null) {
                file.diskWrite(runAt, runLength == run.length ? run : Arrays.copyOf(run, runLength));
            }
        }

        byte[] read(long offset, int length, DatabaseFile file) {
            if (length == 0) {
                return new byte[0];
            }
            if (length < 0 || offset < 0) {
                throw new NexException("invalid read in the database file");
            }
            byte[] out = new byte[length];
            int filled = 0;
            while (filled < length) {
                long at = offset + filled;
                TransactionLog.Span span = covering(at);
                if (span == null) {
                    long next = offset + length;
                    Long start = spans.ceilingKey(at);
                    if (start != null && start < next) {
                        next = start;
                    }
                    int count = (int) (next - at);
                    if (count <= 0) {
                        throw new NexException("invalid read in the database file");
                    }
                    System.arraycopy(readDisk(file, at, count), 0, out, filled, count);
                    filled += count;
                } else {
                    int count = (int) Math.min(span.end() - at, (long) length - filled);
                    if (span.data != null) {
                        System.arraycopy(span.data, (int) (at - span.offset), out, filled, count);
                    }
                    filled += count;
                }
            }
            return out;
        }

        private TransactionLog.Span covering(long at) {
            Map.Entry<Long, TransactionLog.Span> floor = spans.floorEntry(at);
            if (floor == null) {
                return null;
            }
            TransactionLog.Span span = floor.getValue();
            if (at >= span.offset && at < span.end()) {
                return span;
            }
            return null;
        }

        private void put(TransactionLog.Span incoming) {
            long start = incoming.offset;
            long end = incoming.end();
            List<TransactionLog.Span> keep = new ArrayList<>();
            Map.Entry<Long, TransactionLog.Span> floor = spans.floorEntry(start);
            if (floor != null) {
                TransactionLog.Span span = floor.getValue();
                if (span.offset < start && span.end() > start) {
                    keep.add(span.slice(span.offset, start));
                    if (span.end() > end) {
                        keep.add(span.slice(end, span.end()));
                    }
                    spans.remove(span.offset);
                }
            }
            List<Long> remove = new ArrayList<>();
            for (TransactionLog.Span span : spans.tailMap(start).values()) {
                if (span.offset >= end) {
                    break;
                }
                remove.add(span.offset);
                if (span.end() > end) {
                    keep.add(span.slice(end, span.end()));
                }
            }
            for (Long key : remove) {
                spans.remove(key);
            }
            for (TransactionLog.Span span : keep) {
                spans.put(span.offset, span);
            }
            spans.put(start, incoming);
            if (end > fileSize) {
                fileSize = end;
            }
        }

        private static byte[] readDisk(DatabaseFile file, long offset, int length) {
            try {
                long size = file.channel.size();
                byte[] out = new byte[length];
                if (offset >= size) {
                    return out;
                }
                int available = (int) Math.min(length, size - offset);
                System.arraycopy(file.readPhysical(offset, available), 0, out, 0, available);
                return out;
            } catch (IOException exception) {
                throw new NexException("failed to read the database file: " + exception.getMessage(), exception);
            }
        }
    }
}
