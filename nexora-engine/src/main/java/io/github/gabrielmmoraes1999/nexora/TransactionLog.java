package io.github.gabrielmmoraes1999.nexora;

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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Registro sequencial ao lado do {@code .db}. Cada commit acrescenta a
 * transação confirmada e o REDO das páginas, os dois comprimidos. O arquivo
 * não mostra o SQL nem os valores. O checkpoint marca até onde as páginas
 * acompanham o log, sem apagar o histórico. O log só volta ao cabeçalho no
 * backup: a partir daí ele guarda as transações feitas depois.
 */
final class TransactionLog implements AutoCloseable {

    /**
     * Teste de queda: depois que o REDO está no disco e antes de aplicar as páginas.
     */
    static volatile boolean crashAfterRedo;

    final Path path;
    private final FileChannel channel;
    private FileLock lock;
    private long logId;
    private int pageSize;
    private long epoch = 1;
    /**
     * Fim do prefixo íntegro. Um registro rasgado no final não entra aqui.
     */
    private long consistentEnd;

    private TransactionLog(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    static Path beside(Path database) {
        Path name = database.getFileName();
        String text = name == null ? "nexora.db" : name.toString();
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
                throw new NexException("o log de transação já existe");
            }
            if (Files.exists(path)) {
                Files.delete(path);
            }
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            TransactionLog log = new TransactionLog(path, channel);
            try {
                log.lock();
                log.logId = logId;
                log.pageSize = pageSize;
                log.epoch = Format.normalizeEpoch(epoch);
                log.writeHeader();
                log.force();
                log.consistentEnd = Format.LOG_HEADER_SIZE;
                return log;
            } catch (RuntimeException exception) {
                log.close();
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // o arquivo incompleto fica para a próxima tentativa
                }
                throw exception;
            }
        } catch (NexException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new NexException("não foi possível criar o log de transação: " + exception.getMessage(), exception);
        }
    }

    static TransactionLog open(Path database, long logId, int pageSize) {
        return openAt(beside(database), logId, pageSize, true);
    }

    /**
     * Não há log ao lado do banco: o arquivo não existe ou está vazio,
     * mesmo depois de retomar uma cópia interrompida.
     */
    static boolean missing(Path database) {
        Path path = beside(database);
        try {
            absorbPending(path);
            return !Files.isRegularFile(path) || Files.size(path) == 0;
        } catch (IOException exception) {
            throw new NexException("não foi possível abrir o log de transação: " + exception.getMessage(), exception);
        }
    }

    /** Abre um log avulso, o que o aplicador vai sobrepor ao banco. */
    static TransactionLog openFile(Path logFile, long logId, int pageSize) {
        return openAt(logFile, logId, pageSize, false, true);
    }

    /** Abre o log só para ler as transações, sem exigir o par do banco. */
    static TransactionLog inspect(Path logFile) {
        if (logFile == null) {
            throw new NexException("o log de transação é obrigatório");
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
                throw new NexException("log de transação ausente");
            }
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
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
        } catch (NexException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new NexException("não foi possível abrir o log de transação: " + exception.getMessage(), exception);
        }
    }

    long epoch() {
        return epoch;
    }

    /**
     * O backup já copiou o par. O log vivo passa a guardar só o que vier depois.
     */
    void restart(long newEpoch) {
        epoch = Format.normalizeEpoch(newEpoch);
        writeHeader();
        force();
    }

    /** Atualiza a geração no cabeçalho e preserva os registros já gravados. */
    void adoptEpoch(long newEpoch) {
        epoch = Format.normalizeEpoch(newEpoch);
        byte[] header = Format.readFully(channel, 0, Format.LOG_HEADER_SIZE);
        ByteBuffer.wrap(header).order(Format.ORDER).putLong(Format.LOG_EPOCH_AT, epoch);
        Format.writeFully(channel, 0, header);
        force();
    }

    /**
     * Substitui este log pelo conteúdo de {@code source}. Um arquivo
     * {@code .log.apply} ao lado permite retomar se a cópia parar no meio.
     */
    void copyFrom(TransactionLog source) {
        Path pending = pendingPath(path);
        try {
            writeChannel(source.channel, source.size(), pending);
            installPending(pending);
            deleteQuiet(pending);
            consistentEnd = size();
        } catch (IOException exception) {
            throw new NexException("falha ao copiar o log de transação: " + exception.getMessage(), exception);
        }
    }

    void transferTo(Path target) {
        try {
            writeChannel(channel, size(), target);
        } catch (IOException exception) {
            throw new NexException("falha ao copiar o log de transação: " + exception.getMessage(), exception);
        }
    }

    /** Os primeiros {@code length} bytes dos dois logs são iguais. */
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
            throw new NexException("falha ao ler o log de transação: " + exception.getMessage(), exception);
        }
    }

    long consistentEnd() {
        return consistentEnd;
    }

    void appendRedo(Collection<Span> spans, long fileSize) {
        ByteWriter payload = new ByteWriter();
        payload.u64(fileSize);
        int pieces = 0;
        ByteWriter body = new ByteWriter();
        for (Span span : spans) {
            long remaining = span.length;
            long offset = span.offset;
            int dataAt = 0;
            while (remaining > 0) {
                int chunk = (int) Math.min(Integer.MAX_VALUE, remaining);
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
        payload.u32(pieces);
        payload.raw(body.toArray(), 0, body.size());
        append(Format.RECORD_REDO, payload.toArray());
    }

    void appendCheckpoint() {
        append(Format.RECORD_CHECKPOINT, new byte[0]);
    }

    /** Grava o SQL confirmado neste commit. O aplicador executa esses comandos. */
    void appendTransaction(List<Applied> statements) {
        if (statements == null || statements.isEmpty()) {
            return;
        }
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

    /**
     * Transações cujo registro começa em {@code from} ou depois.
     * Cada lote é um commit, com o offset final do registro.
     */
    List<Batch> readTransactions(long from) {
        return readTransactions(from, null);
    }

    /**
     * Transações cujo registro começa em {@code from} ou depois.
     * {@code seen} recebe o offset já lido, para o export mostrar o andamento.
     */
    List<Batch> readTransactions(long from, LongConsumer seen) {
        long size = size();
        if (size < Format.LOG_HEADER_SIZE) {
            throw new NexException("log de transação está corrompido");
        }
        List<Batch> batches = new ArrayList<>();
        long at = Format.LOG_HEADER_SIZE;
        while (at + 4 <= size) {
            if (seen != null) {
                seen.accept(at);
            }
            int recordLength = readInt(at);
            if (recordLength < 20 || at + recordLength > size) {
                break;
            }
            byte[] record = Format.readFully(channel, at, recordLength);
            int stored = ByteBuffer.wrap(record, recordLength - 4, 4).order(Format.ORDER).getInt();
            int expected = Format.crc(record, 4, recordLength - 8);
            if (stored != expected) {
                if (at + recordLength < size) {
                    throw new NexException("log de transação está corrompido");
                }
                break;
            }
            int type = ByteBuffer.wrap(record, 4, 4).order(Format.ORDER).getInt();
            long lsn = ByteBuffer.wrap(record, 8, 8).order(Format.ORDER).getLong();
            if (lsn != at) {
                throw new NexException("log de transação está corrompido");
            }
            if (at >= from && type == Format.RECORD_TRANSACTION) {
                batches.add(new Batch(at + recordLength, decodeTransaction(readable(record))));
            } else if (type != Format.RECORD_REDO && type != Format.RECORD_CHECKPOINT && type != Format.RECORD_TRANSACTION) {
                throw new NexException("log de transação está corrompido");
            }
            at += recordLength;
        }
        if (seen != null) {
            seen.accept(size);
        }
        return batches;
    }

    /**
     * Reaplica os REDO completos que começam em {@code checkpointLsn} ou depois.
     * Registro incompleto ou com CRC inválido no fim do arquivo é descartado.
     */
    boolean replay(long checkpointLsn, BiConsumer<Long, List<Span>> redo) {
        long size = size();
        consistentEnd = Math.min(size, Format.LOG_HEADER_SIZE);
        if (size < Format.LOG_HEADER_SIZE) {
            throw new NexException("log de transação está corrompido");
        }
        long at = Format.LOG_HEADER_SIZE;
        boolean applied = false;
        while (at + 4 <= size) {
            int recordLength = readInt(at);
            if (recordLength < 20 || at + recordLength > size) {
                consistentEnd = at;
                break;
            }
            byte[] record = Format.readFully(channel, at, recordLength);
            int stored = ByteBuffer.wrap(record, recordLength - 4, 4).order(Format.ORDER).getInt();
            int expected = Format.crc(record, 4, recordLength - 8);
            if (stored != expected) {
                if (at + recordLength < size) {
                    throw new NexException("log de transação está corrompido");
                }
                consistentEnd = at;
                break;
            }
            int type = ByteBuffer.wrap(record, 4, 4).order(Format.ORDER).getInt();
            long lsn = ByteBuffer.wrap(record, 8, 8).order(Format.ORDER).getLong();
            if (lsn != at) {
                throw new NexException("log de transação está corrompido");
            }
            if (at >= checkpointLsn && type == Format.RECORD_REDO) {
                byte[] plain = readable(record);
                redo.accept(fileSizeOf(plain), spansOf(plain));
                applied = true;
            } else if (type != Format.RECORD_REDO && type != Format.RECORD_CHECKPOINT && type != Format.RECORD_TRANSACTION) {
                throw new NexException("log de transação está corrompido");
            }
            at += recordLength;
            consistentEnd = at;
        }
        return applied;
    }

    void force() {
        try {
            channel.force(true);
        } catch (IOException exception) {
            throw new NexException("falha ao sincronizar o log de transação: " + exception.getMessage(), exception);
        }
    }

    void truncate(long size) {
        try {
            if (channel.size() != size) {
                channel.truncate(size);
            }
            consistentEnd = size;
        } catch (IOException exception) {
            throw new NexException("falha ao ajustar o log de transação: " + exception.getMessage(), exception);
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

    private void append(int type, byte[] payload) {
        byte[] stored = pack(payload);
        long lsn = size();
        byte[] body = new byte[12 + stored.length];
        ByteBuffer buffer = ByteBuffer.wrap(body).order(Format.ORDER);
        buffer.putInt(type);
        buffer.putLong(lsn);
        System.arraycopy(stored, 0, body, 12, stored.length);
        int crc = Format.crc(body, 0, body.length);
        byte[] record = new byte[4 + body.length + 4];
        ByteBuffer frame = ByteBuffer.wrap(record).order(Format.ORDER);
        frame.putInt(record.length);
        frame.put(body);
        frame.putInt(crc);
        Format.writeFully(channel, lsn, record);
        consistentEnd = lsn + record.length;
    }

    private static final int MAX_PLAIN = 256 * 1024 * 1024;

    /** Comprime o corpo do registro e embaralha os bytes para o arquivo não expor o texto. */
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
            throw new NexException("log de transação está corrompido");
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
            throw new NexException("log de transação está corrompido");
        }
        byte[] copy = packed.clone();
        mask(copy);
        int rawLength = ByteBuffer.wrap(copy, 0, 4).order(Format.ORDER).getInt();
        if (rawLength < 0 || rawLength > MAX_PLAIN) {
            throw new NexException("log de transação está corrompido");
        }
        try {
            byte[] plain = inflate(Arrays.copyOfRange(copy, 4, copy.length), rawLength);
            if (plain.length != rawLength) {
                throw new NexException("log de transação está corrompido");
            }
            return plain;
        } catch (DataFormatException exception) {
            throw new NexException("log de transação está corrompido");
        }
    }

    private static void mask(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] ^= (byte) (0xA5 ^ (i * 31));
        }
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
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
                    throw new DataFormatException("bloco incompleto");
                }
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    throw new DataFormatException("bloco incompleto");
                }
                if (out.size() > MAX_PLAIN - count) {
                    throw new DataFormatException("bloco grande demais");
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
            throw new NexException("log de transação ausente");
        }
        byte[] header = Format.readFully(channel, 0, Format.LOG_HEADER_SIZE);
        if (!Arrays.equals(header, 0, Format.LOG_MAGIC.length, Format.LOG_MAGIC, 0, Format.LOG_MAGIC.length)) {
            throw new NexException(matchIdentity ? "log não pertence a este banco" : "arquivo não é um log de transação");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        int version = buffer.getShort(Format.VERSION_OFFSET) & 0xFFFF;
        if (version != Format.LOG_VERSION) {
            throw new NexException("versão de log não suportada: " + version);
        }
        logId = buffer.getLong(Format.LOG_ID_AT);
        pageSize = buffer.getInt(Format.LOG_PAGE_AT);
        epoch = Format.normalizeEpoch(buffer.getLong(Format.LOG_EPOCH_AT));
        if (matchIdentity && (logId != expectedId || pageSize != expectedPage)) {
            throw new NexException("log não pertence a este banco");
        }
    }

    private void readIdentity() {
        byte[] header = Format.readFully(channel, 0, Format.LOG_HEADER_SIZE);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        logId = buffer.getLong(Format.LOG_ID_AT);
        pageSize = buffer.getInt(Format.LOG_PAGE_AT);
        epoch = Format.normalizeEpoch(buffer.getLong(Format.LOG_EPOCH_AT));
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
     * Conclui uma cópia interrompida. Se o log vivo já está maior, a cópia
     * pendente ficou para trás e pode sair.
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
            // a próxima abertura retoma o que ficou
        }
    }

    private int readInt(long offset) {
        byte[] data = Format.readFully(channel, offset, 4);
        return ByteBuffer.wrap(data).order(Format.ORDER).getInt();
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
            throw new NexException("log de transação está corrompido");
        }
        List<Span> spans = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 13) {
                throw new NexException("log de transação está corrompido");
            }
            int kind = buffer.get() & 0xFF;
            long offset = buffer.getLong();
            int length = buffer.getInt();
            if ((kind != 0 && kind != 1) || offset < 0 || length < 0) {
                throw new NexException("log de transação está corrompido");
            }
            byte[] data = null;
            if (kind == 0) {
                if (buffer.remaining() < length) {
                    throw new NexException("log de transação está corrompido");
                }
                data = new byte[length];
                buffer.get(data);
            }
            spans.add(new Span(offset, length, data));
        }
        if (buffer.remaining() != 0) {
            throw new NexException("log de transação está corrompido");
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
                throw new NexException("o banco já está aberto");
            }
        } catch (NexException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new NexException("não foi possível bloquear o log de transação: " + exception.getMessage(), exception);
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

    /** Um comando SQL confirmado, com o esquema da sessão e os parâmetros. */
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

    /** Um commit do log: os comandos e o offset em que o registro termina. */
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
            throw new NexException("tipo de parâmetro não suportado no log: " + value.getClass().getName());
        }
    }

    private static List<Applied> decodeTransaction(byte[] record) {
        ByteBuffer buffer = ByteBuffer.wrap(record, 16, record.length - 20).order(Format.ORDER);
        if (buffer.remaining() < 4) {
            throw new NexException("log de transação está corrompido");
        }
        int count = buffer.getInt();
        if (count < 0) {
            throw new NexException("log de transação está corrompido");
        }
        List<Applied> statements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String schema = readUtf(buffer);
            String sql = readUtf(buffer);
            if (buffer.remaining() < 4) {
                throw new NexException("log de transação está corrompido");
            }
            int params = buffer.getInt();
            if (params < 0) {
                throw new NexException("log de transação está corrompido");
            }
            Object[] values = new Object[params];
            for (int p = 0; p < params; p++) {
                values[p] = readParam(buffer);
            }
            statements.add(new Applied(schema, sql, values));
        }
        if (buffer.remaining() != 0) {
            throw new NexException("log de transação está corrompido");
        }
        return statements;
    }

    private static Object readParam(ByteBuffer buffer) {
        if (buffer.remaining() < 1) {
            throw new NexException("log de transação está corrompido");
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
                    throw new NexException("log de transação está corrompido");
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
            default -> throw new NexException("log de transação está corrompido");
        };
    }

    private static int readByte(ByteBuffer buffer) {
        if (buffer.remaining() < 1) {
            throw new NexException("log de transação está corrompido");
        }
        return buffer.get() & 0xFF;
    }

    private static int readInt(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            throw new NexException("log de transação está corrompido");
        }
        return buffer.getInt();
    }

    private static long readLong(ByteBuffer buffer) {
        if (buffer.remaining() < 8) {
            throw new NexException("log de transação está corrompido");
        }
        return buffer.getLong();
    }

    private static String readUtf(ByteBuffer buffer) {
        int length = readInt(buffer);
        if (length < 0 || buffer.remaining() < length) {
            throw new NexException("log de transação está corrompido");
        }
        byte[] data = new byte[length];
        buffer.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }
}
