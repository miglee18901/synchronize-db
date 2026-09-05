package org.example.sync;

public final class SyncRunResult {
    private final long initialOffset;
    private final long finalOffset;
    private final int scanned;
    private final int synchronizedRows;
    private final int errors;

    SyncRunResult(long initialOffset, long finalOffset, int scanned, int synchronizedRows, int errors) {
        this.initialOffset = initialOffset;
        this.finalOffset = finalOffset;
        this.scanned = scanned;
        this.synchronizedRows = synchronizedRows;
        this.errors = errors;
    }

    public long getInitialOffset() {
        return initialOffset;
    }

    public long getFinalOffset() {
        return finalOffset;
    }

    public int getScanned() {
        return scanned;
    }

    public int getSynchronizedRows() {
        return synchronizedRows;
    }

    public int getErrors() {
        return errors;
    }
}
