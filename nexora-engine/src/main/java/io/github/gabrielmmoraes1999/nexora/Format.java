package io.github.gabrielmmoraes1999.nexora;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

final class Format {

    static final byte[] MAGIC = "NEXORANEX".getBytes(StandardCharsets.US_ASCII);
    static final byte[] DB_MAGIC = "NEXORADBF".getBytes(StandardCharsets.US_ASCII);
    static final byte[] LOG_MAGIC = "NEXORALOG".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 6;
    /** A partir desta versão o registro traz o tamanho declarado no bloco. */
    static final int VARIABLE_RECORDS = 3;
    static final int DB_VERSION = 1;
    static final int LOG_VERSION = 1;
    static final int HEADER_SIZE = 64;
    static final int DB_HEADER_SIZE = 256;
    static final int LOG_HEADER_SIZE = 64;
    static final int VERSION_OFFSET = MAGIC.length;
    static final int PAGE_SIZE_OFFSET = 64;
    static final int CATALOG_SPAN_OFFSET = 68;
    /** Identificador compartilhado pelo .db e pelo .log. Zero não é um banco novo. */
    static final int LOG_ID_OFFSET = 72;
    /** Offset no log até onde as páginas do .db estão sincronizadas. */
    static final int CHECKPOINT_LSN_OFFSET = 80;
    /** Reservado. O checkpoint deixa o histórico no log e este byte fica em 0. */
    static final int CHECKPOINT_STABLE_OFFSET = 88;
    static final byte CHECKPOINT_STABLE = 1;
    /** Geração do log. Sobe no backup completo e quando o log vivo falta e outro é criado. */
    static final int LOG_EPOCH_OFFSET = 96;
    /** Até onde um log externo já foi aplicado, no sistema de offsets desse log. */
    static final int APPLIED_OFFSET = 104;
    static final int LOG_ID_AT = 16;
    static final int LOG_PAGE_AT = 24;
    static final int LOG_EPOCH_AT = 32;
    static final int RECORD_REDO = 1;
    static final int RECORD_CHECKPOINT = 2;
    /** SQL confirmado: as transações que o aplicador executa de novo. */
    static final int RECORD_TRANSACTION = 3;
    static final int DEFAULT_PAGE_SIZE = 8192;
    static final int CATALOG_MAGIC = 0x43415441;
    static final int SLOT_A = 16;
    static final int SLOT_B = 40;
    static final int SCRUB_OFFSET = VERSION_OFFSET + 2;
    static final byte SCRUB_DIRTY = 1;
    static final byte SCRUB_CLEAN = 2;
    static final int BLOCK_HEADER = 24;
    static final int META_MAGIC = 0x4D455441;
    static final int SLOT_MAGIC = 0x534C4F54;
    static final int BLOB_MAGIC = 0x424C4F42;
    static final int JOURNAL_MAGIC = 0x434D4954;
    static final int INDEX_MAGIC = 0x49445842;
    static final int INDEX_OVERFLOW_MAGIC = 0x4944584F;
    static final int INDEX_PAGE = 4096;
    static final int MAX_NAME = 128;
    static final int MAX_COLUMNS = 1024;
    static final int MAX_CHAR = 8192;
    static final int INLINE_CHAR = 256;
    static final int MAX_PAYLOAD = 8 * 1024 * 1024;
    static final ByteOrder ORDER = ByteOrder.BIG_ENDIAN;

    private Format() {
    }

    static long align8(long value) {
        return (value + 7L) & ~7L;
    }

    /** Geração 0 no arquivo antigo deste formato equivale à primeira geração. */
    static long normalizeEpoch(long stored) {
        return stored == 0L ? 1L : stored;
    }

    static int checkPageSize(int pageSize) {
        if (pageSize == 4096 || pageSize == 8192 || pageSize == 16384 || pageSize == 32768) {
            return pageSize;
        }
        throw new NexException("tamanho de página inválido: use 4096, 8192, 16384 ou 32768");
    }

    static long alignPage(long value, int pageSize) {
        long mask = pageSize - 1L;
        long aligned = (value + mask) & ~mask;
        if (value < 0 || aligned < value) {
            throw new NexException("tamanho excede o limite do arquivo");
        }
        return aligned;
    }

    static long blobExtent(long payloadLength) {
        if (payloadLength < 0) {
            throw new NexException("tamanho de BLOB inválido");
        }
        long raw = BLOCK_HEADER + payloadLength;
        if (raw > Integer.MAX_VALUE) {
            throw new NexException("BLOB excede o tamanho máximo suportado");
        }
        return align8(raw);
    }

    static long slotExtent(int payloadLength) {
        if (payloadLength < 0) {
            throw new NexException("tamanho de registro inválido");
        }
        long raw = BLOCK_HEADER + payloadLength;
        if (raw > Integer.MAX_VALUE) {
            throw new NexException("registro excede o tamanho máximo suportado");
        }
        return align8(raw);
    }

    static int crc(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    static int slotCrc(long generation, long metaOffset, int metaLength) {
        ByteBuffer buffer = ByteBuffer.allocate(20).order(ORDER);
        buffer.putLong(generation);
        buffer.putLong(metaOffset);
        buffer.putInt(metaLength);
        return crc(buffer.array(), 0, 20);
    }

    static void zero(FileChannel channel, long offset, long length) {
        if (length == 0) {
            return;
        }
        if (offset < 0 || length < 0) {
            throw new NexException("intervalo inválido ao limpar o arquivo");
        }
        byte[] chunk = new byte[(int) Math.min(1024 * 1024L, length)];
        long at = offset;
        long left = length;
        while (left > 0) {
            int count = (int) Math.min(chunk.length, left);
            writeFully(channel, at, count == chunk.length ? chunk : new byte[count]);
            at += count;
            left -= count;
        }
    }

    static void writeFully(FileChannel channel, long offset, byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long position = offset;
        try {
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer, position);
                if (written < 0) {
                    throw new java.io.EOFException("não foi possível gravar o arquivo da tabela");
                }
                position += written;
            }
        } catch (java.io.IOException exception) {
            throw new NexException("falha ao gravar o arquivo da tabela: " + exception.getMessage(), exception);
        }
    }

    static byte[] readFully(FileChannel channel, long offset, int length) {
        byte[] data = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long position = offset;
        try {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position);
                if (read < 0) {
                    throw new NexException("arquivo da tabela truncado ou corrompido");
                }
                position += read;
            }
        } catch (NexException exception) {
            throw exception;
        } catch (java.io.IOException exception) {
            throw new NexException("falha ao ler o arquivo da tabela: " + exception.getMessage(), exception);
        }
        return data;
    }
}
