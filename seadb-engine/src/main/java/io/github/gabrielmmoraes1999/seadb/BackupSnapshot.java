package io.github.gabrielmmoraes1999.seadb;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/** Disk-backed before-images. The caller serializes reads and physical mutations. */
final class BackupSnapshot implements AutoCloseable {
    private static final int BLOCK = 64 * 1024;
    private final FileChannel source;
    private final FileChannel saved;
    private final long size;
    private final Map<Long, Long> blocks = new HashMap<>();
    private long savedLength;
    private boolean changed;

    BackupSnapshot(FileChannel source, long size) {
        this.source = source;
        this.size = size;
        Path temp = null;
        try {
            temp = Files.createTempFile("seadb-backup-", ".snapshot");
            saved = FileChannel.open(temp, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE);
        } catch (IOException exception) {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
            throw new SeaException("could not create backup snapshot", exception);
        }
    }

    void preserve(long offset, long length) {
        if (length <= 0) return;
        changed = true;
        long end = Math.min(size, offset + length);
        for (long block = offset / BLOCK; block * BLOCK < end; block++) {
            if (blocks.containsKey(block)) continue;
            long at = block * BLOCK;
            int count = (int) Math.min(BLOCK, size - at);
            byte[] bytes = Format.readFully(source, at, count);
            Format.writeFully(saved, savedLength, bytes);
            blocks.put(block, savedLength);
            savedLength += count;
        }
    }

    boolean changed() { return changed; }

    byte[] read(long offset, int length) {
        byte[] result = new byte[length];
        int copied = 0;
        while (copied < length) {
            long at = offset + copied;
            int count = Math.min(length - copied, BLOCK - (int) (at % BLOCK));
            Long savedAt = blocks.get(at / BLOCK);
            byte[] bytes = savedAt == null ? Format.readFully(source, at, count)
                    : Format.readFully(saved, savedAt + at % BLOCK, count);
            System.arraycopy(bytes, 0, result, copied, count);
            copied += count;
        }
        return result;
    }

    @Override
    public void close() {
        try { saved.close(); }
        catch (IOException exception) { throw new SeaException("could not close backup snapshot", exception); }
    }
}
