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
import java.util.List;

/**
 * Um único arquivo {@code .xdb}. A página 0 é o cabeçalho. O catálogo e
 * cada tabela ocupam páginas inteiras desse arquivo.
 */
final class DatabaseFile implements AutoCloseable {

    final Path path;
    final FileChannel channel;
    private FileLock lock;
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

    private DatabaseFile(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    static long capacityFor(long logical, int pageSize) {
        long needed = Math.max(logical, (long) pageSize);
        long slack = Math.max(pageSize, needed / 8);
        return Format.alignPage(needed + slack, pageSize);
    }

    static DatabaseFile open(Path file) {
        return open(file, Format.DEFAULT_PAGE_SIZE, true);
    }

    static DatabaseFile create(Path file, int pageSize) {
        return open(file, Format.checkPageSize(pageSize), false);
    }

    private static DatabaseFile open(Path file, int pageSize, boolean existing) {
        if (file == null) {
            throw new NexException("o arquivo do banco é obrigatório");
        }
        Path path = file.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            throw new NexException("o banco é um arquivo .xdb, não um diretório");
        }
        try {
            if (existing) {
                if (!Files.isRegularFile(path)) {
                    throw new NexException("o arquivo do banco não existe");
                }
                if (Files.size(path) == 0) {
                    throw new NexException("o arquivo do banco está vazio");
                }
            } else if (Files.exists(path) && Files.size(path) > 0) {
                throw new NexException("o arquivo do banco já existe");
            }
            Path parent = path.getParent();
            if (!existing && parent != null) {
                Files.createDirectories(parent);
            }
            boolean created = !existing && !Files.exists(path);
            FileChannel channel = existing
                    ? FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
                    : FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            DatabaseFile database = new DatabaseFile(path, channel);
            try {
                database.lock();
                if (created || channel.size() == 0) {
                    database.pageSize = pageSize;
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
                    } catch (IOException ignored) {
                        // o arquivo vazio fica para a próxima tentativa
                    }
                }
                throw exception;
            }
        } catch (NexException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new NexException("não foi possível abrir o banco: " + exception.getMessage(), exception);
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
     * Copia uma região para um buraco anterior, sem sobrepor a origem.
     * O catálogo passa a apontar para o destino antes de o espaço antigo ser limpo.
     */
    void relocate(long from, long to, long length, List<StoredTable> nextTables) {
        if (length <= 0 || to < pageSize || from < pageSize || to >= from || to + length > from) {
            throw new NexException("falha interna ao compactar o banco");
        }
        if (overlaps(to, length, catalogOffset, catalogSpan) || overlaps(from, length, catalogOffset, catalogSpan)) {
            throw new NexException("falha interna ao compactar o banco");
        }
        copy(from, to, length);
        force();
        Allocator draft = draft();
        draft.take(to, length);
        draft.free(from, length);
        retire(from, length);
        draft.free(catalogOffset, catalogSpan());
        publish(nextTables, draft);
        scrubPending();
    }

    void copy(long from, long to, long length) {
        if (length < 0 || from < 0 || to < 0) {
            throw new NexException("cópia inválida no arquivo do banco");
        }
        if (length == 0) {
            return;
        }
        if (from < to + length && to < from + length) {
            throw new NexException("cópia sobreposta no arquivo do banco");
        }
        long remaining = length;
        long readAt = from;
        long writeAt = to;
        while (remaining > 0) {
            int chunk = (int) Math.min(1024 * 1024L, remaining);
            byte[] data = Format.readFully(channel, readAt, chunk);
            Format.writeFully(channel, writeAt, data);
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
        Format.zero(channel, offset, length);
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
        byte[] header = Format.readFully(channel, 0, Format.DB_HEADER_SIZE);
        if (header[Format.SCRUB_OFFSET] == Format.SCRUB_DIRTY) {
            return;
        }
        header[Format.SCRUB_OFFSET] = Format.SCRUB_DIRTY;
        Format.writeFully(channel, 0, header);
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
            throw new NexException("falha interna ao gravar o catálogo");
        }
        byte[] stored = new byte[(int) span];
        System.arraycopy(catalog, 0, stored, 0, catalog.length);
        boolean flipped = false;
        boolean installed = false;
        int target = activeSlot == 0 ? 1 : 0;
        long nextGeneration = generation + 1;
        try {
            Format.writeFully(channel, at, stored);
            force();
            byte[] header = Format.readFully(channel, 0, Format.DB_HEADER_SIZE);
            ByteBuffer.wrap(header).order(Format.ORDER).putInt(Format.CATALOG_SPAN_OFFSET, (int) span);
            header[Format.SCRUB_OFFSET] = Format.SCRUB_DIRTY;
            FileHeader.writeSlot(header, target, nextGeneration, at, catalog.length);
            Format.writeFully(channel, 0, header);
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
        try {
            channel.force(true);
        } catch (IOException exception) {
            throw new NexException("falha ao sincronizar o arquivo do banco: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void close() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException ignored) {
            // o canal será fechado em seguida
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // o bloqueio deixa de valer quando o processo solta o arquivo
        }
        lock = null;
    }

    private void createEmpty() {
        byte[] draft = CatalogCodec.encode(0, List.of(), List.of());
        long span = pagedLength(draft.length);
        long size = pageSize + span;
        byte[] catalog = CatalogCodec.encode(size, List.of(), List.of());
        if (catalog.length != draft.length) {
            throw new NexException("falha interna ao criar o banco");
        }
        byte[] header = new byte[pageSize];
        System.arraycopy(Format.DB_MAGIC, 0, header, 0, Format.DB_MAGIC.length);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        buffer.putShort(Format.VERSION_OFFSET, (short) Format.DB_VERSION);
        buffer.putInt(Format.PAGE_SIZE_OFFSET, pageSize);
        buffer.putInt(Format.CATALOG_SPAN_OFFSET, (int) span);
        header[Format.SCRUB_OFFSET] = Format.SCRUB_CLEAN;
        FileHeader.writeSlot(header, 0, 1L, pageSize, catalog.length);
        Format.writeFully(channel, 0, header);
        Format.writeFully(channel, pageSize, paged(catalog));
        force();
        install(size, List.of(), List.of(), pageSize, catalog.length, span, 1L, 0);
        truncateChannel(size);
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
            throw new NexException("catálogo excede o tamanho máximo suportado");
        }
        return span;
    }

    private void recover() {
        byte[] header = Format.readFully(channel, 0, Format.DB_HEADER_SIZE);
        if (!Arrays.equals(header, 0, Format.DB_MAGIC.length, Format.DB_MAGIC, 0, Format.DB_MAGIC.length)) {
            throw new NexException("arquivo não é um banco do Nexora");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        int version = buffer.getShort(Format.VERSION_OFFSET) & 0xFFFF;
        if (version != Format.DB_VERSION) {
            throw new NexException("versão de banco não suportada: " + version);
        }
        pageSize = Format.checkPageSize(buffer.getInt(Format.PAGE_SIZE_OFFSET));
        int reserved = buffer.getInt(Format.CATALOG_SPAN_OFFSET);
        FileHeader slots = FileHeader.parseSlots(header);
        FileHeader.Slot active = slots.active();
        if (reserved <= 0 || reserved % pageSize != 0 || reserved < active.metaLength()) {
            throw new NexException("catálogo do banco está corrompido");
        }
        byte[] encoded = Format.readFully(channel, active.metaOffset(), active.metaLength());
        CatalogImage image = CatalogCodec.decode(encoded, pageSize);
        if (active.metaOffset() + reserved > image.fileSize) {
            throw new NexException("catálogo do banco está corrompido");
        }
        install(image.fileSize, image.free, image.tables, active.metaOffset(), active.metaLength(),
                reserved, active.generation(), slots.activeIndex());
        truncateChannel(fileSize);
        ensureScrubbed();
        boolean pending = false;
        for (StoredTable table : tables) {
            if (table.pending != null) {
                applyFlip(table);
                pending = true;
            }
        }
        if (pending) {
            force();
            List<StoredTable> clean = new ArrayList<>();
            for (StoredTable table : tables) {
                clean.add(new StoredTable(table.name, table.base, table.capacity, null));
            }
            Allocator allocator = draft();
            allocator.free(catalogOffset, catalogSpan());
            publish(clean, allocator);
            scrubPending();
        }
    }

    private void applyFlip(StoredTable table) {
        PendingFlip flip = table.pending;
        byte[] header = Format.readFully(channel, table.base, Format.HEADER_SIZE);
        FileHeader parsed = FileHeader.parse(header);
        if (parsed.active().generation() >= flip.generation) {
            return;
        }
        header[Format.SCRUB_OFFSET] = Format.SCRUB_DIRTY;
        FileHeader.writeSlot(header, flip.slot, flip.generation, flip.metaOffset, flip.metaLength);
        Format.writeFully(channel, table.base, header);
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
        return Format.readFully(channel, 0, Format.DB_HEADER_SIZE)[Format.SCRUB_OFFSET];
    }

    private void markScrubClean() {
        byte[] header = Format.readFully(channel, 0, Format.DB_HEADER_SIZE);
        if (header[Format.SCRUB_OFFSET] == Format.SCRUB_CLEAN) {
            return;
        }
        header[Format.SCRUB_OFFSET] = Format.SCRUB_CLEAN;
        Format.writeFully(channel, 0, header);
        force();
    }

    private void wipe(List<Extent> ranges) {
        List<Extent> victims = Extent.intersect(ranges, freeExtents);
        for (Extent extent : victims) {
            if (extent.offset < pageSize || extent.offset + extent.length > fileSize) {
                throw new NexException("espaço livre fora do arquivo");
            }
            if (overlaps(extent.offset, extent.length, catalogOffset, catalogSpan)) {
                throw new NexException("espaço livre invade o catálogo");
            }
            for (StoredTable table : tables) {
                if (overlaps(extent.offset, extent.length, table.base, table.capacity)) {
                    throw new NexException("espaço livre invade a tabela " + table.name);
                }
            }
        }
        if (victims.isEmpty()) {
            return;
        }
        for (Extent extent : victims) {
            Format.zero(channel, extent.offset, extent.length);
        }
        force();
    }

    private static boolean overlaps(long left, long leftLength, long right, long rightLength) {
        return leftLength > 0 && rightLength > 0 && left < right + rightLength && right < left + leftLength;
    }

    private void truncateChannel(long size) {
        try {
            long current = channel.size();
            if (current > size) {
                channel.truncate(size);
            } else if (current < size) {
                ByteBuffer zero = ByteBuffer.allocate(1);
                if (channel.write(zero, size - 1) != 1) {
                    throw new NexException("falha ao ajustar o arquivo do banco");
                }
            }
        } catch (NexException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new NexException("falha ao ajustar o arquivo do banco: " + exception.getMessage(), exception);
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
                throw new NexException("o banco já está aberto");
            }
        } catch (NexException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new NexException("não foi possível bloquear o banco: " + exception.getMessage(), exception);
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
                throw new NexException("falha interna ao reservar espaço no banco");
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
                throw new NexException("catálogo do banco está corrompido");
            }
            int stored = ByteBuffer.wrap(data, data.length - 4, 4).order(Format.ORDER).getInt();
            if (stored != Format.crc(data, 0, data.length - 4)) {
                throw new NexException("catálogo do banco falhou na verificação");
            }
            ByteReader reader = new ByteReader(data, data.length - 4);
            if (reader.u32() != Format.CATALOG_MAGIC) {
                throw new NexException("catálogo do banco está corrompido");
            }
            int version = reader.u32();
            if (version != Format.DB_VERSION) {
                throw new NexException("versão de catálogo não suportada: " + version);
            }
            long size = reader.u64();
            int count = reader.u32();
            if (count < 0) {
                throw new NexException("catálogo do banco está corrompido");
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
                    throw new NexException("catálogo do banco está corrompido");
                }
                tables.add(new StoredTable(name, base, capacity, pending));
            }
            int freeCount = reader.u32();
            if (freeCount < 0) {
                throw new NexException("catálogo do banco está corrompido");
            }
            List<Extent> free = new ArrayList<>(freeCount);
            for (int i = 0; i < freeCount; i++) {
                long offset = reader.u64();
                long length = reader.u64();
                if (offset < pageSize || offset % pageSize != 0 || length <= 0 || length % pageSize != 0) {
                    throw new NexException("catálogo do banco está corrompido");
                }
                free.add(new Extent(offset, length));
            }
            int pad = reader.u16();
            reader.skip(pad);
            if (reader.remaining() != 0) {
                throw new NexException("catálogo do banco está corrompido");
            }
            if (size < pageSize || size % pageSize != 0) {
                throw new NexException("catálogo do banco está corrompido");
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
                throw new NexException("nome longo demais no catálogo");
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
                throw new NexException("catálogo do banco está corrompido");
            }
        }
    }
}
