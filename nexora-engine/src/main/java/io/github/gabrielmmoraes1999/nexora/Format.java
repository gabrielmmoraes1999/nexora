package io.github.gabrielmmoraes1999.nexora;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.zip.CRC32;

final class Format {

    static final byte[] MAGIC = "NEXORANEX".getBytes(StandardCharsets.US_ASCII);
    static final byte[] DB_MAGIC = "NEXORADBF".getBytes(StandardCharsets.US_ASCII);
    static final byte[] LOG_MAGIC = "NEXORALOG".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;
    static final int DB_VERSION = 1;
    static final int LOG_VERSION = 1;
    static final int HEADER_SIZE = 64;
    static final int DB_HEADER_SIZE = 256;
    static final int LOG_HEADER_SIZE = 64;
    static final int VERSION_OFFSET = MAGIC.length;
    static final int PAGE_SIZE_OFFSET = 64;
    static final int CATALOG_SPAN_OFFSET = 68;
    /** Identifier shared by the .db and the .log. Zero is not a new database. */
    static final int LOG_ID_OFFSET = 72;
    /** Offset in the log up to which the .db pages are synchronized. */
    static final int CHECKPOINT_LSN_OFFSET = 80;
    /** Reserved. The checkpoint leaves the history in the log and this byte stays 0. */
    static final int CHECKPOINT_STABLE_OFFSET = 88;
    static final byte CHECKPOINT_STABLE = 1;
    /** Log generation. It increases on a full backup and when the live log is missing and a new one is created. */
    static final int LOG_EPOCH_OFFSET = 96;
    /** How far an external log has already been applied, in that log's offset space. */
    static final int APPLIED_OFFSET = 104;
    static final int LOG_ID_AT = 16;
    static final int LOG_PAGE_AT = 24;
    static final int LOG_EPOCH_AT = 32;
    static final int RECORD_REDO = 1;
    static final int RECORD_CHECKPOINT = 2;
    /** Committed SQL: the transactions the applier runs again. */
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

    static int checkPageSize(int pageSize) {
        if (pageSize == 4096 || pageSize == 8192 || pageSize == 16384 || pageSize == 32768) {
            return pageSize;
        }
        throw new NexException("invalid page size: use 4096, 8192, 16384, or 32768");
    }

    static long alignPage(long value, int pageSize) {
        long mask = pageSize - 1L;
        long aligned = (value + mask) & ~mask;
        if (value < 0 || aligned < value) {
            throw new NexException("size exceeds the file limit");
        }
        return aligned;
    }

    static long blobExtent(long payloadLength) {
        if (payloadLength < 0) {
            throw new NexException("invalid BLOB size");
        }
        long raw = BLOCK_HEADER + payloadLength;
        if (raw > Integer.MAX_VALUE) {
            throw new NexException("BLOB exceeds the maximum supported size");
        }
        return align8(raw);
    }

    static long slotExtent(int payloadLength) {
        if (payloadLength < 0) {
            throw new NexException("invalid record size");
        }
        long raw = BLOCK_HEADER + payloadLength;
        if (raw > Integer.MAX_VALUE) {
            throw new NexException("record exceeds the maximum supported size");
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
            throw new NexException("invalid range while clearing the file");
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

    /**
     * Opens a file that stays for the life of the database. On Windows a byte-range
     * lock still allows deletion; this open does not share delete access. Other
     * systems have no such flag and keep the ordinary open.
     */
    static FileChannel openHeld(Path path, OpenOption... options) throws IOException {
        OpenOption[] held = new OpenOption[options.length + 1];
        System.arraycopy(options, 0, held, 0, options.length);
        held[options.length] = com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE;
        try {
            return FileChannel.open(path, held);
        } catch (UnsupportedOperationException unsupported) {
            return FileChannel.open(path, options);
        }
    }

    static void writeFully(FileChannel channel, long offset, byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long position = offset;
        try {
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer, position);
                if (written < 0) {
                    throw new java.io.EOFException("could not write the table file");
                }
                position += written;
            }
        } catch (java.io.IOException exception) {
            throw new NexException("failed to write the table file: " + exception.getMessage(), exception);
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
                    throw new NexException("table file is truncated or corrupt");
                }
                position += read;
            }
        } catch (NexException exception) {
            throw exception;
        } catch (java.io.IOException exception) {
            throw new NexException("failed to read the table file: " + exception.getMessage(), exception);
        }
        return data;
    }
}
