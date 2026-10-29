package io.github.gabrielmmoraes1999.seadb;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Page cache shared by the databases of one server.
 * The maximum is the RAM those pages may occupy. A read brings the page in.
 * When the pool is full, the least recently used page is dropped.
 * Writes go to the file first, then the cached copy is discarded, so a dropped
 * page is already on disk.
 */
public final class Cache {

    /** Embedded and headless opens use this when no size was given. */
    public static final long DEFAULT_BYTES = 64L * 1024 * 1024;

    private final Object lock = new Object();
    private final long requested;
    private long maximum;
    private long resident;
    private final LinkedHashMap<Key, byte[]> pages = new LinkedHashMap<>(16, 0.75f, true);

    private Cache(long requested) {
        this.requested = requested;
        this.maximum = requested;
    }

    public static Cache of(long bytes) {
        if (bytes < 1) {
            throw new SeaException("invalid cache size");
        }
        return new Cache(bytes);
    }

    /**
     * Turns {@code 8M}, {@code 2048K}, {@code 32}, {@code 8MB}, or {@code 2048KB} into bytes.
     * A bare number is megabytes.
     */
    public static long bytes(String text) {
        if (text == null || text.isBlank()) {
            throw new SeaException("invalid cache size");
        }
        String raw = text.trim().replace(" ", "").toUpperCase(Locale.ROOT);
        long unit = 1024L * 1024L;
        String number = raw;
        if (raw.endsWith("MB")) {
            number = raw.substring(0, raw.length() - 2);
        } else if (raw.endsWith("KB")) {
            number = raw.substring(0, raw.length() - 2);
            unit = 1024L;
        } else if (raw.endsWith("M")) {
            number = raw.substring(0, raw.length() - 1);
        } else if (raw.endsWith("K")) {
            number = raw.substring(0, raw.length() - 1);
            unit = 1024L;
        }
        if (number.isEmpty()) {
            throw new SeaException("invalid cache size: " + text);
        }
        try {
            long value = Long.parseLong(number);
            if (value < 1) {
                throw new SeaException("invalid cache size: " + text);
            }
            long bytes = value * unit;
            if (bytes / unit != value) {
                throw new SeaException("invalid cache size: " + text);
            }
            return bytes;
        } catch (NumberFormatException exception) {
            throw new SeaException("invalid cache size: " + text);
        }
    }

    /**
     * Rounds the request up to a whole number of pages, and to at least one page.
     */
    public static long adjust(long requested, int pageSize) {
        if (requested < 1) {
            throw new SeaException("invalid cache size");
        }
        if (pageSize < 1) {
            return requested;
        }
        if (requested <= pageSize) {
            return pageSize;
        }
        long pages = (requested + pageSize - 1L) / pageSize;
        long adjusted = pages * (long) pageSize;
        if (adjusted < requested) {
            throw new SeaException("invalid cache size");
        }
        return adjusted;
    }

    public long maximum() {
        synchronized (lock) {
            return maximum;
        }
    }

    /** Grows the pool so it can hold at least one page of this database. */
    void accept(int pageSize) {
        long adjusted = adjust(requested, pageSize);
        synchronized (lock) {
            if (adjusted > maximum) {
                maximum = adjusted;
            }
        }
    }

    byte[] read(Path file, FileChannel channel, int pageSize, long offset, int length) {
        if (length == 0) {
            return new byte[0];
        }
        if (length < 0 || offset < 0 || pageSize < 1) {
            throw new SeaException("invalid read in the database file");
        }
        synchronized (lock) {
            long size;
            try {
                size = channel.size();
            } catch (IOException exception) {
                throw new SeaException("failed to read the database file: " + exception.getMessage(), exception);
            }
            if (offset > size || (long) length > size - offset) {
                throw new SeaException("table file is truncated or corrupt");
            }
            byte[] out = new byte[length];
            int filled = 0;
            while (filled < length) {
                long at = offset + filled;
                long index = at / pageSize;
                long pageStart = index * (long) pageSize;
                int within = (int) (at - pageStart);
                byte[] page = load(file, channel, pageSize, index, pageStart, size);
                int count = Math.min(pageSize - within, length - filled);
                System.arraycopy(page, within, out, filled, count);
                filled += count;
            }
            return out;
        }
    }

    void forget(Path file, int pageSize, long offset, long length) {
        if (length <= 0 || pageSize < 1 || offset < 0) {
            return;
        }
        long end = offset + length;
        if (end < offset) {
            return;
        }
        String name = name(file);
        long first = offset / pageSize;
        long last = (end - 1) / pageSize;
        synchronized (lock) {
            for (long index = first; index <= last; index++) {
                drop(new Key(name, index));
            }
        }
    }

    void release(Path file) {
        String name = name(file);
        synchronized (lock) {
            pages.entrySet().removeIf(entry -> {
                if (!entry.getKey().file.equals(name)) {
                    return false;
                }
                resident -= entry.getValue().length;
                return true;
            });
        }
    }

    int pages() {
        synchronized (lock) {
            return pages.size();
        }
    }

    long resident() {
        synchronized (lock) {
            return resident;
        }
    }

    private byte[] load(Path file, FileChannel channel, int pageSize, long index, long pageStart, long fileSize) {
        Key key = new Key(name(file), index);
        byte[] page = pages.get(key);
        if (page != null && page.length == pageSize) {
            return page;
        }
        if (page != null) {
            drop(key);
        }
        byte[] fresh = new byte[pageSize];
        if (pageStart < fileSize) {
            int available = (int) Math.min(pageSize, fileSize - pageStart);
            byte[] got = Format.readFully(channel, pageStart, available);
            System.arraycopy(got, 0, fresh, 0, got.length);
        }
        evict(fresh.length);
        pages.put(key, fresh);
        resident += fresh.length;
        return fresh;
    }

    private void evict(int incoming) {
        var iterator = pages.entrySet().iterator();
        while (resident + incoming > maximum && iterator.hasNext()) {
            Map.Entry<Key, byte[]> eldest = iterator.next();
            resident -= eldest.getValue().length;
            iterator.remove();
        }
    }

    private void drop(Key key) {
        byte[] removed = pages.remove(key);
        if (removed != null) {
            resident -= removed.length;
        }
    }

    private static String name(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    private record Key(String file, long index) {
    }
}
