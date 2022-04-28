package com.tyron.builder.internal.operations;


/**
 * Detail included in {@link OperationProgressEvent} instances to indicate some general progress of the build operation.
 */
public class OperationProgressDetails {
    private final long progress;
    private final long total;
    private final String units;

    public OperationProgressDetails(long progress, long total, String units) {
        this.progress = progress;
        this.total = total;
        this.units = units;
    }

    public long getProgress() {
        return progress;
    }

    public long getTotal() {
        return total;
    }

    public String getUnits() {
        return units;
    }
}