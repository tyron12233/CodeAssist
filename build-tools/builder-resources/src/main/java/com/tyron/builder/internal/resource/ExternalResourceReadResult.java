package com.tyron.builder.internal.resource;

import javax.annotation.Nullable;

/**
 * @since 4.0
 */
public class ExternalResourceReadResult<T> {

    private final long bytesRead;
    private final T result;

    private ExternalResourceReadResult(long bytesRead, T result) {
        this.bytesRead = bytesRead;
        this.result = result;
    }

    public static ExternalResourceReadResult<Void> of(long bytesRead) {
        return new ExternalResourceReadResult<Void>(bytesRead, null);
    }

    public static <T> ExternalResourceReadResult<T> of(long bytesRead, T t) {
        return new ExternalResourceReadResult<T>(bytesRead, t);
    }

    /**
     * The number of <b>content</b> bytes read.
     * <p>
     * This is not guaranteed to be the number of bytes <b>transferred</b>.
     * For example, this resource may be content encoded (e.g. compression, fewer bytes transferred).
     * Or, it might be transfer encoded (e.g. HTTP chunked transfer, more bytes transferred).
     * Or, both.
     * Therefore, it is not necessarily an accurate input into transfer rate (a.k.a. throughput) calculations.
     * <p>
     * Moreover, it represents the content bytes <b>read</b>, not transferred.
     * If the read operation only reads a subset of what was transmitted, this number will be the read byte count.
     */
    public long getBytesRead() {
        return bytesRead;
    }

    /**
     * Any final result of the read operation.
     */
    @Nullable
    public T getResult() {
        return result;
    }
}
