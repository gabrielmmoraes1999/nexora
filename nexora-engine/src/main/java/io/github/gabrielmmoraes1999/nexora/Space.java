package io.github.gabrielmmoraes1999.nexora;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class Extent {
    final long offset;
    final long length;

    Extent(long offset, long length) {
        this.offset = offset;
        this.length = length;
    }

    static List<Extent> coalesce(List<Extent> extents) {
        List<Extent> sorted = new ArrayList<>(extents);
        sorted.sort(Comparator.comparingLong(extent -> extent.offset));
        List<Extent> merged = new ArrayList<>();
        for (Extent extent : sorted) {
            if (extent.length <= 0) {
                continue;
            }
            if (!merged.isEmpty()) {
                Extent last = merged.get(merged.size() - 1);
                long end = last.offset + last.length;
                if (end > extent.offset) {
                    throw new NexException("espaço livre da tabela está corrompido");
                }
                if (end == extent.offset) {
                    merged.set(merged.size() - 1, new Extent(last.offset, last.length + extent.length));
                    continue;
                }
            }
            merged.add(extent);
        }
        return merged;
    }

    static List<Extent> intersect(List<Extent> left, List<Extent> right) {
        List<Extent> hits = new ArrayList<>();
        for (Extent region : left) {
            long regionEnd = region.offset + region.length;
            for (Extent hole : right) {
                long start = Math.max(region.offset, hole.offset);
                long end = Math.min(regionEnd, hole.offset + hole.length);
                if (end > start) {
                    hits.add(new Extent(start, end - start));
                }
            }
        }
        return coalesce(hits);
    }
}

final class Space {
    private final List<Extent> free = new ArrayList<>();
    private long fileSize;

    Space(List<Extent> free, long fileSize) {
        this.free.addAll(Extent.coalesce(free));
        this.fileSize = fileSize;
        trimTail();
    }

    boolean growTail(long offset, long oldLength, long newLength) {
        if (offset < 0 || oldLength < 0 || newLength < oldLength || offset + oldLength != fileSize) {
            return false;
        }
        fileSize = offset + newLength;
        return true;
    }

    long allocate(long size) {
        size = Format.align8(size);
        for (int i = 0; i < free.size(); i++) {
            Extent extent = free.get(i);
            if (extent.length >= size) {
                free.remove(i);
                if (extent.length > size) {
                    free.add(i, new Extent(extent.offset + size, extent.length - size));
                }
                return extent.offset;
            }
        }
        long offset = fileSize;
        fileSize += size;
        return offset;
    }

    long fileSize() {
        return fileSize;
    }

    void release(long offset, long length) {
        if (length <= 0) {
            return;
        }
        free.add(new Extent(offset, length));
        List<Extent> merged = Extent.coalesce(free);
        free.clear();
        free.addAll(merged);
        trimTail();
    }

    void take(long offset, long length) {
        if (length <= 0) {
            throw new NexException("falha interna ao compactar o banco");
        }
        long end = offset + length;
        for (int i = 0; i < free.size(); i++) {
            Extent extent = free.get(i);
            long extentEnd = extent.offset + extent.length;
            if (end <= extent.offset || offset >= extentEnd) {
                continue;
            }
            if (offset < extent.offset || end > extentEnd) {
                throw new NexException("falha interna ao compactar o banco");
            }
            free.remove(i);
            if (offset > extent.offset) {
                free.add(i++, new Extent(extent.offset, offset - extent.offset));
            }
            if (end < extentEnd) {
                free.add(i, new Extent(end, extentEnd - end));
            }
            return;
        }
        throw new NexException("falha interna ao compactar o banco");
    }

    private void trimTail() {
        if (free.isEmpty()) {
            return;
        }
        Extent last = free.get(free.size() - 1);
        if (last.offset + last.length == fileSize) {
            fileSize = last.offset;
            free.remove(free.size() - 1);
        }
    }

    List<Extent> remaining() {
        return new ArrayList<>(free);
    }
}
