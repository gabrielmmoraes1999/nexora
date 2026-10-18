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
 * Opening and closing write the checkpoint.
 */
final class DatabaseFile implements AutoCloseable {

    final Path path;
    final FileChannel channel;
    private FileLock lock;
    private TransactionLog log;
    private long logId;
    private long epoch = 1;
    private long appliedOffset;
    private long checkpointLsn = Format.LOG_HEADER_SIZE;
    private boolean stable;
    private CommitBuffer commit;
    private boolean crashed;
    private boolean recoveryPending;
    private boolean ready;
    private boolean appliedToDisk;
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
        return open(file, Format.DEFAULT_PAGE_SIZE, true, cache);
    }

    static DatabaseFile create(Path file, int pageSize) {
        return open(file, Format.checkPageSize(pageSize), false, null);
    }

    private static DatabaseFile open(Path file, int pageSize, boolean existing, Cache cache) {
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
            commit.write(offset, data);
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
        Format.writeFully(channel, offset, data);
        if (pageSize > 0) {
            cache.forget(path, pageSize, offset, data.length);
        }
    }

    private void diskZero(long offset, long length) {
        Format.zero(channel, offset, length);
        if (pageSize > 0) {
            cache.forget(path, pageSize, offset, length);
        }
    }

    private byte[] readPhysical(long offset, int length) {
        if (pageSize > 0) {
            return cache.read(path, channel, pageSize, offset, length);
        }
        return Format.readFully(channel, offset, length);
    }

    long cacheMaximum() {
        return cache.maximum();
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
        if (commit != null || crashed) {
            return;
        }
        forceChannel();
    }

    @Override
    public void close() {
        try {
            saveCheckpoint();
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
        if (TransactionLog.missing(path)) {
            long next = epoch + 1;
            writeEpoch(next);
            writeAppliedOffset(0L);
            writeCheckpointLsn(Format.LOG_HEADER_SIZE);
            forceChannel();
            log = TransactionLog.create(path, logId, pageSize, next);
        } else {
            log = TransactionLog.open(path, logId, pageSize);
        }
        long logEpoch = log.epoch();
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
        boolean applied = log.replay(checkpointLsn, this::applyRedo);
        log.truncate(log.consistentEnd());
        if (checkpointLsn > log.size()) {
            throw new NexException("transaction log does not cover the checkpoint");
        }
        if (logEpoch != epoch) {
            writeEpoch(logEpoch);
        }
        if (applied || log.size() > checkpointLsn || logEpoch != epoch) {
            checkpoint();
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
            saveCheckpoint();
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
            saveCheckpoint();
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
        finishCommit(List.of());
    }

    void finishCommit(List<TransactionLog.Applied> statements) {
        if (commit == null) {
            return;
        }
        CommitBuffer buffer = commit;
        commit = null;
        if (buffer.unchanged()) {
            return;
        }
        if (statements != null && !statements.isEmpty()) {
            log.appendTransaction(statements);
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
        if (!ready || log == null || crashed || (recoveryPending && !appliedToDisk)) {
            return;
        }
        checkpoint();
    }

    private void checkpoint() {
        forceChannel();
        if (log.size() > checkpointLsn) {
            log.appendCheckpoint();
            log.force();
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
        long next = epoch + 1;
        log.restart(next);
        writeEpoch(next);
        writeAppliedOffset(0L);
        writeCheckpointLsn(Format.LOG_HEADER_SIZE);
        forceChannel();
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
            checkpoint();
            return ApplyPlan.none();
        }
        TransactionLog external = TransactionLog.openFile(externalPath, logId, pageSize);
        try {
            long incoming = external.epoch();
            if (incoming == epoch) {
                long from = appliedOffset > 0 ? appliedOffset : checkpointLsn;
                if (external.size() < from) {
                    throw new NexException("transaction log does not cover the checkpoint");
                }
                if (external.size() <= from) {
                    return ApplyPlan.none();
                }
                return new ApplyPlan(incoming, false, external.readTransactions(from), external.size());
            }
            if (incoming == epoch + 1) {
                if (log.size() != checkpointLsn) {
                    throw new NexException("the database still has changes without a checkpoint");
                }
                long from = appliedOffset > Format.LOG_HEADER_SIZE ? appliedOffset : Format.LOG_HEADER_SIZE;
                return new ApplyPlan(incoming, true, external.readTransactions(from), external.size());
            }
            if (incoming < epoch) {
                throw new NexException("log is from a previous generation of the database");
            }
            throw new NexException("a log is missing between the database and this file");
        } finally {
            external.close();
        }
    }

    /** Records how far the external log has been applied, so a new apply continues from there. */
    void noteApplied(long end) {
        writeAppliedOffset(end);
        checkpoint();
    }

    /** The next generation becomes this database's generation. */
    void completeApply(ApplyPlan plan) {
        if (plan.advanceEpoch) {
            log.adoptEpoch(plan.incomingEpoch);
            writeEpoch(plan.incomingEpoch);
        }
        if (plan.end > appliedOffset) {
            writeAppliedOffset(plan.end);
        }
        checkpoint();
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

        private ApplyPlan(long incomingEpoch, boolean advanceEpoch, List<TransactionLog.Batch> batches, long end) {
            this.incomingEpoch = incomingEpoch;
            this.advanceEpoch = advanceEpoch;
            this.batches = batches;
            this.end = end;
        }

        static ApplyPlan none() {
            return new ApplyPlan(0L, false, List.of(), 0L);
        }

        boolean idle() {
            return batches.isEmpty() && !advanceEpoch;
        }
    }

    private void writeCheckpointLsn(long lsn) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(Format.ORDER);
        buffer.putLong(lsn);
        diskWrite(Format.CHECKPOINT_LSN_OFFSET, buffer.array());
        checkpointLsn = lsn;
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
