package io.github.gabrielmmoraes1999.nexora;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class FileHeader {

    record Slot(long generation, long metaOffset, int metaLength, boolean valid) {
    }

    final Slot a;
    final Slot b;

    private FileHeader(Slot a, Slot b) {
        this.a = a;
        this.b = b;
    }

    static FileHeader parse(byte[] header) {
        if (header.length < Format.HEADER_SIZE) {
            throw new NexException("cabeçalho da tabela está incompleto");
        }
        if (!Arrays.equals(header, 0, Format.MAGIC.length, Format.MAGIC, 0, Format.MAGIC.length)) {
            throw new NexException("arquivo não é uma tabela do Nexora");
        }
        int version = version(header);
        if (version < 2 || version > Format.VERSION) {
            throw new NexException("versão de arquivo não suportada: " + version);
        }
        return parseSlots(header);
    }

    static int version(byte[] header) {
        return ByteBuffer.wrap(header).order(Format.ORDER).getShort(Format.VERSION_OFFSET) & 0xFFFF;
    }

    static FileHeader parseSlots(byte[] header) {
        return new FileHeader(readSlot(header, Format.SLOT_A), readSlot(header, Format.SLOT_B));
    }

    static byte[] fresh(long metaOffset, int metaLength) {
        byte[] header = new byte[Format.HEADER_SIZE];
        System.arraycopy(Format.MAGIC, 0, header, 0, Format.MAGIC.length);
        ByteBuffer.wrap(header).order(Format.ORDER).putShort(Format.VERSION_OFFSET, (short) Format.VERSION);
        header[Format.SCRUB_OFFSET] = Format.SCRUB_CLEAN;
        writeSlot(header, 0, 1L, metaOffset, metaLength);
        return header;
    }

    static void writeSlot(byte[] header, int slotIndex, long generation, long metaOffset, int metaLength) {
        int at = slotIndex == 0 ? Format.SLOT_A : Format.SLOT_B;
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        buffer.putLong(at, generation);
        buffer.putLong(at + 8, metaOffset);
        buffer.putInt(at + 16, metaLength);
        buffer.putInt(at + 20, Format.slotCrc(generation, metaOffset, metaLength));
    }

    Slot active() {
        Slot best = null;
        if (a.valid) {
            best = a;
        }
        if (b.valid && (best == null || b.generation > best.generation)) {
            best = b;
        }
        if (best == null) {
            throw new NexException("cabeçalho da tabela está corrompido");
        }
        return best;
    }

    int activeIndex() {
        Slot active = active();
        if (a.valid && a.generation == active.generation && a.metaOffset == active.metaOffset && a.metaLength == active.metaLength) {
            return 0;
        }
        return 1;
    }

    private static Slot readSlot(byte[] header, int at) {
        ByteBuffer buffer = ByteBuffer.wrap(header).order(Format.ORDER);
        long generation = buffer.getLong(at);
        long metaOffset = buffer.getLong(at + 8);
        int metaLength = buffer.getInt(at + 16);
        int crc = buffer.getInt(at + 20);
        boolean valid = generation > 0
                && metaLength > 0
                && metaOffset >= Format.HEADER_SIZE
                && crc == Format.slotCrc(generation, metaOffset, metaLength);
        return new Slot(generation, metaOffset, metaLength, valid);
    }
}
