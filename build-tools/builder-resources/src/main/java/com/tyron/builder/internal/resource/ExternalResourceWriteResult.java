package com.tyron.builder.internal.resource;

public class ExternalResourceWriteResult {
    private final long bytesWritten;

    public ExternalResourceWriteResult(long bytesWritten) {
        this.bytesWritten = bytesWritten;
    }

    /**
     * The number of <em>content</em> bytes written. This is not necessarily the same as the number of bytes transferred.
     */
    long getBytesWritten() {
        return bytesWritten;
    }
}
