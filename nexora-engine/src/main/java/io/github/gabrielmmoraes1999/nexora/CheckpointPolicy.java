package io.github.gabrielmmoraes1999.nexora;

/**
 * When an automatic checkpoint runs. A checkpoint stores dirty pages; it does not commit.
 * The defaults are loose so a short test does not checkpoint on its own.
 */
public final class CheckpointPolicy {

    /** No automatic checkpoint. {@code Database.checkpoint()} still runs one. */
    public static final CheckpointPolicy MANUAL = new CheckpointPolicy(0, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 2);

    private final long intervalMillis;
    private final int dirtyTables;
    private final long dirtyBytes;
    private final long logBytes;
    private final double cachePressure;

    /**
     * @param intervalMillis how long after the previous checkpoint, or {@code 0} to ignore time
     * @param dirtyTables    open transactions touching at least this many tables
     * @param dirtyBytes     estimated bytes of uncommitted row changes
     * @param logBytes       bytes appended since the checkpoint LSN
     * @param cachePressure  resident cache divided by its maximum, from 0 to 1; above 1 disables it
     */
    public CheckpointPolicy(long intervalMillis, int dirtyTables, long dirtyBytes, long logBytes, double cachePressure) {
        if (intervalMillis < 0 || dirtyTables < 1 || dirtyBytes < 1 || logBytes < 1 || cachePressure <= 0) {
            throw new NexException("invalid checkpoint policy");
        }
        this.intervalMillis = intervalMillis;
        this.dirtyTables = dirtyTables;
        this.dirtyBytes = dirtyBytes;
        this.logBytes = logBytes;
        this.cachePressure = cachePressure;
    }

    /** Thirty seconds, 32 dirty tables, 8 MiB of changes, 16 MiB of log, or 80% of the cache. */
    public static CheckpointPolicy defaults() {
        return new CheckpointPolicy(30_000, 32, 8L * 1024 * 1024, 16L * 1024 * 1024, 0.8);
    }

    long intervalMillis() {
        return intervalMillis;
    }

    int dirtyTables() {
        return dirtyTables;
    }

    long dirtyBytes() {
        return dirtyBytes;
    }

    long logBytes() {
        return logBytes;
    }

    double cachePressure() {
        return cachePressure;
    }
}
