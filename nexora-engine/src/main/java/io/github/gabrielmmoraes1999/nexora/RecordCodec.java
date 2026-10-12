package io.github.gabrielmmoraes1999.nexora;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;

final class Cell {
    static final Object ON_DISK = new Object();

    final Object value;
    final long offset;
    final long length;

    Cell(Object value, long offset, long length) {
        this.value = value;
        this.offset = offset;
        this.length = length;
    }

    static Cell sqlNull() {
        return new Cell(null, -1, 0);
    }

    boolean isNull() {
        return value == null;
    }

    boolean onDisk() {
        return value == ON_DISK;
    }
}

final class StoredRow {
    final long id;
    final Cell[] cells;
    final long slotOffset;
    final long slotSize;
    final boolean dirty;

    StoredRow(long id, Cell[] cells, long slotOffset, long slotSize, boolean dirty) {
        this.id = id;
        this.cells = cells;
        this.slotOffset = slotOffset;
        this.slotSize = slotSize;
        this.dirty = dirty;
    }

    Cell[] copyCells() {
        return cells.clone();
    }
}

final class RecordCodec {

    private RecordCodec() {
    }

    static int maxPayloadSize(Schema schema) {
        int size = nullBytes(schema);
        for (Column column : schema.columns) {
            size += maxFieldSize(column);
        }
        return size;
    }

    static int encodedSize(Schema schema, Cell[] cells) {
        int size = nullBytes(schema);
        for (int i = 0; i < schema.columns.size(); i++) {
            if (!cells[i].isNull()) {
                size += valueSize(schema.columns.get(i), cells[i]);
            }
        }
        return size;
    }

    static byte[] encode(Schema schema, Cell[] cells) {
        ByteBuffer buffer = ByteBuffer.allocate(encodedSize(schema, cells)).order(Format.ORDER);
        int bitmapAt = buffer.position();
        buffer.position(bitmapAt + nullBytes(schema));
        for (int i = 0; i < schema.columns.size(); i++) {
            Column column = schema.columns.get(i);
            Cell cell = cells[i];
            if (cell.isNull()) {
                buffer.put(bitmapAt + (i / 8), (byte) (buffer.get(bitmapAt + (i / 8)) | (1 << (i % 8))));
                continue;
            }
            writeField(buffer, column, cell);
        }
        if (buffer.position() != buffer.capacity()) {
            throw new NexException("falha interna ao gravar o registro de " + schema.name);
        }
        return buffer.array();
    }

    static Cell[] decode(Schema schema, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(Format.ORDER);
        int nullBytes = nullBytes(schema);
        if (payload.length < nullBytes) {
            throw new NexException("registro incompatível com o esquema da tabela " + schema.name);
        }
        byte[] bitmap = new byte[nullBytes];
        buffer.get(bitmap);
        Cell[] cells = new Cell[schema.columns.size()];
        for (int i = 0; i < cells.length; i++) {
            Column column = schema.columns.get(i);
            boolean isNull = (bitmap[i / 8] & (1 << (i % 8))) != 0;
            if (isNull) {
                cells[i] = Cell.sqlNull();
                continue;
            }
            cells[i] = readField(buffer, column);
        }
        if (buffer.hasRemaining()) {
            throw new NexException("registro incompatível com o esquema da tabela " + schema.name);
        }
        return cells;
    }

    static int fixedPayloadSize(Schema schema) {
        int size = nullBytes(schema);
        for (Column column : schema.columns) {
            size += fixedFieldSize(column);
        }
        return size;
    }

    static Cell[] decodeFixed(Schema schema, byte[] payload) {
        if (payload.length != fixedPayloadSize(schema)) {
            throw new NexException("registro incompatível com o esquema da tabela " + schema.name);
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(Format.ORDER);
        int nullBytes = nullBytes(schema);
        byte[] bitmap = new byte[nullBytes];
        buffer.get(bitmap);
        Cell[] cells = new Cell[schema.columns.size()];
        for (int i = 0; i < cells.length; i++) {
            Column column = schema.columns.get(i);
            boolean isNull = (bitmap[i / 8] & (1 << (i % 8))) != 0;
            if (isNull) {
                buffer.position(buffer.position() + fixedFieldSize(column));
                cells[i] = Cell.sqlNull();
                continue;
            }
            cells[i] = readFixedField(buffer, column);
        }
        return cells;
    }

    private static int nullBytes(Schema schema) {
        return (schema.columns.size() + 7) / 8;
    }

    private static int maxFieldSize(Column column) {
        return switch (column.type) {
            case BIGINT, DOUBLE, TIMESTAMP -> 8;
            case INTEGER, FLOAT, DATE, TIME -> 4;
            case SMALLINT -> 2;
            case BOOLEAN -> 1;
            case DECIMAL -> decimalWidth(column.length);
            case CHAR, VARCHAR -> column.heapBacked() ? 16 : 2 + column.length * 4;
            case BLOB -> 16;
        };
    }

    private static int fixedFieldSize(Column column) {
        return switch (column.type) {
            case BIGINT, DOUBLE, TIMESTAMP, DATE, TIME -> 8;
            case INTEGER, FLOAT -> 4;
            case SMALLINT -> 2;
            case BOOLEAN -> 1;
            case DECIMAL -> 2 + column.length + 2;
            case CHAR, VARCHAR -> column.heapBacked() ? 16 : 4 + column.length * 4;
            case BLOB -> 16;
        };
    }

    private static int valueSize(Column column, Cell cell) {
        return switch (column.type) {
            case CHAR, VARCHAR -> {
                if (column.heapBacked()) {
                    yield 16;
                }
                yield 2 + textBytes(column, (String) cell.value).length;
            }
            default -> maxFieldSize(column);
        };
    }

    private static int decimalWidth(int precision) {
        if (precision <= 4) {
            return 2;
        }
        if (precision <= 9) {
            return 4;
        }
        if (precision <= 18) {
            return 8;
        }
        return 16;
    }

    private static void writeField(ByteBuffer buffer, Column column, Cell cell) {
        switch (column.type) {
            case INTEGER -> buffer.putInt(((Number) cell.value).intValue());
            case BIGINT -> buffer.putLong(((Number) cell.value).longValue());
            case SMALLINT -> buffer.putShort(((Number) cell.value).shortValue());
            case FLOAT -> buffer.putFloat(((Number) cell.value).floatValue());
            case DOUBLE -> buffer.putDouble((Double) cell.value);
            case BOOLEAN -> buffer.put((byte) ((Boolean) cell.value ? 1 : 0));
            case DATE -> buffer.putInt(epochDay((LocalDate) cell.value));
            case TIME -> buffer.putInt(timeMillis((LocalTime) cell.value));
            case TIMESTAMP -> buffer.putLong(((LocalDateTime) cell.value).toInstant(ZoneOffset.UTC).toEpochMilli());
            case DECIMAL -> writeDecimal(buffer, (BigDecimal) cell.value, column.length);
            case CHAR, VARCHAR -> {
                if (column.heapBacked()) {
                    writeRef(buffer, cell);
                } else {
                    writeInlineText(buffer, column, (String) cell.value);
                }
            }
            case BLOB -> writeRef(buffer, cell);
        }
    }

    private static Cell readField(ByteBuffer buffer, Column column) {
        return switch (column.type) {
            case INTEGER -> new Cell(buffer.getInt(), -1, 0);
            case BIGINT -> new Cell(buffer.getLong(), -1, 0);
            case SMALLINT -> new Cell(buffer.getShort(), -1, 0);
            case FLOAT -> new Cell(buffer.getFloat(), -1, 0);
            case DOUBLE -> new Cell(buffer.getDouble(), -1, 0);
            case TIME -> new Cell(LocalTime.ofNanoOfDay(Integer.toUnsignedLong(buffer.getInt()) * 1_000_000L), -1, 0);
            case BOOLEAN -> {
                int raw = buffer.get() & 0xFF;
                if (raw != 0 && raw != 1) {
                    throw new NexException("booleano corrompido na tabela");
                }
                yield new Cell(raw == 1, -1, 0);
            }
            case DATE -> new Cell(LocalDate.ofEpochDay(buffer.getInt()), -1, 0);
            case TIMESTAMP -> new Cell(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(buffer.getLong()), ZoneOffset.UTC), -1, 0);
            case DECIMAL -> new Cell(readDecimal(buffer, column.length, column.scale), -1, 0);
            case CHAR, VARCHAR -> column.heapBacked()
                    ? readRef(column, buffer)
                    : new Cell(readInlineText(buffer, column), -1, 0);
            case BLOB -> readRef(column, buffer);
        };
    }

    private static Cell readFixedField(ByteBuffer buffer, Column column) {
        return switch (column.type) {
            case INTEGER -> new Cell(buffer.getInt(), -1, 0);
            case BIGINT -> new Cell(buffer.getLong(), -1, 0);
            case SMALLINT -> new Cell(buffer.getShort(), -1, 0);
            case FLOAT -> new Cell(buffer.getFloat(), -1, 0);
            case DOUBLE -> new Cell(buffer.getDouble(), -1, 0);
            case TIME -> new Cell(LocalTime.ofNanoOfDay(buffer.getLong()), -1, 0);
            case BOOLEAN -> {
                int raw = buffer.get() & 0xFF;
                if (raw != 0 && raw != 1) {
                    throw new NexException("booleano corrompido na tabela");
                }
                yield new Cell(raw == 1, -1, 0);
            }
            case DATE -> new Cell(LocalDate.ofEpochDay(buffer.getLong()), -1, 0);
            case TIMESTAMP -> new Cell(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(buffer.getLong()), ZoneOffset.UTC), -1, 0);
            case DECIMAL -> new Cell(new BigDecimal(readPadded(buffer, column.length + 2)), -1, 0);
            case CHAR, VARCHAR -> column.heapBacked()
                    ? readRef(column, buffer)
                    : new Cell(readPaddedText(buffer, column.length * 4), -1, 0);
            case BLOB -> readRef(column, buffer);
        };
    }

    private static void writeRef(ByteBuffer buffer, Cell cell) {
        long offset = cell.offset > 0 ? cell.offset : 0L;
        long length = cell.offset > 0 ? cell.length : 0L;
        buffer.putLong(offset);
        buffer.putLong(length);
    }

    private static Cell readRef(Column column, ByteBuffer buffer) {
        long offset = buffer.getLong();
        long length = buffer.getLong();
        if (offset == 0 && length == 0) {
            if (column.type == DataType.BLOB) {
                return new Cell(new byte[0], 0, 0);
            }
            return new Cell("", 0, 0);
        }
        if (offset <= 0 || length < 0) {
            throw new NexException("referência de BLOB corrompida na coluna " + column.name);
        }
        return new Cell(Cell.ON_DISK, offset, length);
    }

    private static void writeInlineText(ByteBuffer buffer, Column column, String text) {
        byte[] raw = textBytes(column, text);
        buffer.putShort((short) raw.length);
        buffer.put(raw);
    }

    private static byte[] textBytes(Column column, String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        int maxBytes = column.length * 4;
        if (raw.length > maxBytes) {
            throw new NexException("texto excede o espaço da coluna " + column.name);
        }
        return raw;
    }

    private static String readInlineText(ByteBuffer buffer, Column column) {
        int length = buffer.getShort() & 0xFFFF;
        int maxBytes = column.length * 4;
        if (length > maxBytes || buffer.remaining() < length) {
            throw new NexException("texto corrompido no registro");
        }
        byte[] raw = new byte[length];
        buffer.get(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static String readPaddedText(ByteBuffer buffer, int maxBytes) {
        int length = buffer.getInt();
        if (length < 0 || length > maxBytes) {
            throw new NexException("texto corrompido no registro");
        }
        byte[] raw = new byte[length];
        buffer.get(raw);
        buffer.position(buffer.position() + (maxBytes - length));
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static void writeDecimal(ByteBuffer buffer, BigDecimal value, int precision) {
        int width = decimalWidth(precision);
        byte[] raw = value.unscaledValue().toByteArray();
        if (raw.length == width + 1 && raw[0] == 0) {
            buffer.put(raw, 1, width);
            return;
        }
        if (raw.length > width) {
            throw new NexException("DECIMAL não cabe no campo: " + value.toPlainString());
        }
        int pad = width - raw.length;
        byte fill = value.signum() < 0 ? (byte) 0xFF : 0;
        for (int i = 0; i < pad; i++) {
            buffer.put(fill);
        }
        buffer.put(raw);
    }

    private static BigDecimal readDecimal(ByteBuffer buffer, int precision, int scale) {
        int width = decimalWidth(precision);
        if (buffer.remaining() < width) {
            throw new NexException("DECIMAL corrompido no registro");
        }
        byte[] raw = new byte[width];
        buffer.get(raw);
        return new BigDecimal(new BigInteger(raw), scale);
    }

    private static String readPadded(ByteBuffer buffer, int width) {
        int length = buffer.getShort() & 0xFFFF;
        if (length > width) {
            throw new NexException("DECIMAL corrompido no registro");
        }
        byte[] raw = new byte[length];
        buffer.get(raw);
        buffer.position(buffer.position() + (width - length));
        return new String(raw, StandardCharsets.US_ASCII);
    }

    private static int epochDay(LocalDate date) {
        long day = date.toEpochDay();
        if (day < Integer.MIN_VALUE || day > Integer.MAX_VALUE) {
            throw new NexException("data fora do intervalo que cabe em 4 bytes");
        }
        return (int) day;
    }

    private static int timeMillis(LocalTime time) {
        long millis = time.toNanoOfDay() / 1_000_000L;
        if (millis < 0 || millis > 86_399_999L) {
            throw new NexException("hora fora do intervalo");
        }
        return (int) millis;
    }
}
