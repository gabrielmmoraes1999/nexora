package io.github.gabrielmmoraes1999.seadb;

/**
 * One file of a backup that is still being read from the open database.
 * {@code size} is the length captured at the checkpoint, in bytes.
 */
public record BackupPiece(String name, long size) {

    /** Largest slice returned by one read. The frame limit is larger. */
    public static final int CHUNK = 1024 * 1024;
}
